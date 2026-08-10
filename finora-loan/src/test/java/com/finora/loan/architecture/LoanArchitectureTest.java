package com.finora.loan.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Khóa các chiều dependency để lần bổ sung chức năng sau không gom trách nhiệm trở lại. */
@AnalyzeClasses(packages = "com.finora.loan")
class LoanArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_MUST_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controller..", "..dto..", "..integration..", "..service..", "..repository..");

    @ArchTest
    static final ArchRule MAPPERS_MUST_NOT_QUERY_OR_ORCHESTRATE = noClasses()
            .that().resideInAPackage("..mapper..")
            .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..service..", "..controller..");

    @ArchTest
    static final ArchRule REPOSITORIES_MUST_NOT_CALL_APPLICATION_OR_INTEGRATION = noClasses()
            .that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..", "..integration..", "..controller..");
}
