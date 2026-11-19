package com.hotelsaas.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.hotelsaas")
class ArchitectureTest {
  @ArchTest
  static final ArchRule domainHasNoFrameworkDependencies =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "jakarta.persistence..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule packagesAreAcyclic =
      slices().matching("com.hotelsaas.(*)..").should().beFreeOfCycles();
}
