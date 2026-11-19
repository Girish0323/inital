package com.hotelsaas.common;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DatabaseFoundationTest {
  private static final String ALPHA = "ABC20261117001";
  private static final String BETA = "XYZ20261117001";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("hotelsaas")
          .withUsername("migration")
          .withPassword("migration-only-test-password");

  @BeforeAll
  static void migrate() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("filesystem:../deploy/db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate();
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE ROLE rls_test NOLOGIN");
      statement.execute("GRANT USAGE ON SCHEMA audit TO rls_test");
      statement.execute("GRANT SELECT ON audit.audit_log TO rls_test");
      insertAudit(connection, ALPHA, "tenant-a-record");
      insertAudit(connection, BETA, "tenant-b-record");
    }
  }

  @Test
  void everyTenantTableUsesVarchar14AndForcedRls() throws Exception {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, "
                  + "a.atttypid = 'varchar'::regtype AND a.atttypmod - 4 = 14 AS tenant_id_is_varchar14 "
                  + "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                  + "JOIN pg_attribute a ON a.attrelid=c.oid AND a.attname='tenant_id' AND NOT a.attisdropped "
                  + "WHERE n.nspname IN ('auth','tenant','core','billing','audit') AND c.relkind='r'")) {
        int tables = 0;
        while (rs.next()) {
          tables++;
          assertTrue(rs.getBoolean("tenant_id_is_varchar14"), rs.getString("relname"));
          assertTrue(rs.getBoolean("relrowsecurity"), rs.getString("relname") + " has RLS");
          assertTrue(rs.getBoolean("relforcerowsecurity"), rs.getString("relname") + " forces RLS");
        }
        assertTrue(tables >= 19, "Expected all tenant-scoped foundation tables to be checked");
      }
    }
  }

  @Test
  void rowLevelSecurityHidesOtherTenantsEvenWhenFilteringByTheirId() throws Exception {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE rls_test");
      statement.execute("SET row_security = on");
      statement.execute("SET app.tenant_id = '" + ALPHA + "'");
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT count(*) FROM audit.audit_log WHERE tenant_id='" + BETA + "'")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
      }
      try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM audit.audit_log")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
      }
    }
  }

  @Test
  void seedContainsTwoHotelsFiveRolesAndPendingSetupIdentities() throws Exception {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      assertEquals(5, count(statement, "SELECT count(*) FROM auth.role"));
      assertEquals(
          1,
          count(
              statement,
              "SELECT count(*) FROM auth.platform_admin WHERE role_code='SUPER_ADMIN' "
                  + "AND account_state='PENDING_SETUP' AND password_hash IS NULL"));
      assertEquals(
          2,
          count(
              statement,
              "SELECT count(*) FROM tenant.hotel_tenant "
                  + "WHERE tenant_id IN ('"
                  + ALPHA
                  + "','"
                  + BETA
                  + "')"));
      assertEquals(
          8,
          count(
              statement,
              "SELECT count(*) FROM auth.user_role WHERE tenant_id IN ('"
                  + ALPHA
                  + "','"
                  + BETA
                  + "')"));
    }
  }

  @Test
  void auditRowsRejectUpdatesAndDeletes() throws Exception {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      SQLException update =
          assertThrows(
              SQLException.class,
              () ->
                  statement.executeUpdate(
                      "UPDATE audit.audit_log SET action='tampered' WHERE tenant_id='"
                          + ALPHA
                          + "'"));
      assertEquals("55000", update.getSQLState());
      SQLException delete =
          assertThrows(
              SQLException.class,
              () ->
                  statement.executeUpdate(
                      "DELETE FROM audit.audit_log WHERE tenant_id='" + ALPHA + "'"));
      assertEquals("55000", delete.getSQLState());
    }
  }

  @Test
  void upiReferenceIsUniquePerTenantExceptAfterRejectionAndBillAllowsMultipleRows()
      throws Exception {
    UUID alphaOrder = insertOrder(ALPHA, "ABC202611180001");
    UUID alphaBillOne = insertBill(ALPHA, alphaOrder, "ABC-26-27-0001");
    UUID alphaOrderTwo = insertOrder(ALPHA, "ABC202611180002");
    UUID alphaBillTwo = insertBill(ALPHA, alphaOrderTwo, "ABC-26-27-0002");
    UUID betaOrder = insertOrder(BETA, "XYZ202611180001");
    UUID betaBill = insertBill(BETA, betaOrder, "XYZ-26-27-0001");

    insertUpi(ALPHA, alphaBillOne, "UTR-SHARED", "PENDING_VERIFICATION", "key-a-1", null);
    assertUniqueViolation(
        () ->
            insertUpi(ALPHA, alphaBillTwo, "UTR-SHARED", "PENDING_VERIFICATION", "key-a-2", null));
    insertUpi(BETA, betaBill, "UTR-SHARED", "PENDING_VERIFICATION", "key-b-1", null);

    insertUpi(ALPHA, alphaBillTwo, "UTR-REUSED", "REJECTED", "key-a-3", "Not received");
    insertUpi(ALPHA, alphaBillTwo, "UTR-REUSED", "PENDING_VERIFICATION", "key-a-4", null);

    insertCash(ALPHA, alphaBillOne, "key-a-cash");
    try (Connection connection = adminConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM billing.payment WHERE tenant_id=? AND bill_id=?")) {
      statement.setString(1, ALPHA);
      statement.setObject(2, alphaBillOne);
      try (ResultSet rs = statement.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1), "The database does not force one payment per bill");
      }
    }
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void insertAudit(Connection connection, String tenant, String action)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit.audit_log(tenant_id,actor_id,action,resource_type) VALUES(?,?,?,'TEST')")) {
      statement.setString(1, tenant);
      statement.setString(2, "integration-test");
      statement.setString(3, action);
      statement.executeUpdate();
    }
  }

  private static int count(Statement statement, String sql) throws SQLException {
    try (ResultSet rs = statement.executeQuery(sql)) {
      assertTrue(rs.next());
      return rs.getInt(1);
    }
  }

  private static UUID insertOrder(String tenant, String orderNumber) throws SQLException {
    try (Connection connection = adminConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO core.orders(tenant_id,order_number,business_date) VALUES(?,?,?) RETURNING id")) {
      statement.setString(1, tenant);
      statement.setString(2, orderNumber);
      statement.setObject(3, LocalDate.of(2026, 11, 18));
      try (ResultSet rs = statement.executeQuery()) {
        assertTrue(rs.next());
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private static UUID insertBill(String tenant, UUID orderId, String invoiceNumber)
      throws SQLException {
    try (Connection connection = adminConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO billing.bill(tenant_id,order_id,invoice_number,financial_year,subtotal,total) "
                    + "VALUES(?,?,?,'2026-27',100,100) RETURNING id")) {
      statement.setString(1, tenant);
      statement.setObject(2, orderId);
      statement.setString(3, invoiceNumber);
      try (ResultSet rs = statement.executeQuery()) {
        assertTrue(rs.next());
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private static void insertUpi(
      String tenant, UUID bill, String reference, String state, String key, String rejection)
      throws SQLException {
    try (Connection connection = adminConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO billing.payment(tenant_id,bill_id,payment_method,payment_state,amount,upi_reference,recorded_by,idempotency_key,rejection_reason) "
                    + "VALUES(?,?,'UPI',?,100,?,'integration-test',?,?)")) {
      statement.setString(1, tenant);
      statement.setObject(2, bill);
      statement.setString(3, state);
      statement.setString(4, reference);
      statement.setString(5, key);
      statement.setString(6, rejection);
      statement.executeUpdate();
    }
  }

  private static void insertCash(String tenant, UUID bill, String key) throws SQLException {
    try (Connection connection = adminConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO billing.payment(tenant_id,bill_id,payment_method,payment_state,amount,amount_tendered,change_due,recorded_by,idempotency_key) "
                    + "VALUES(?,?,'CASH','VERIFIED',100,100,0,'integration-test',?)")) {
      statement.setString(1, tenant);
      statement.setObject(2, bill);
      statement.setString(3, key);
      statement.executeUpdate();
    }
  }

  private static void assertUniqueViolation(SqlAction action) throws Exception {
    SQLException error = assertThrows(SQLException.class, action::run);
    assertEquals("23505", error.getSQLState());
  }

  @FunctionalInterface
  private interface SqlAction {
    void run() throws Exception;
  }
}
