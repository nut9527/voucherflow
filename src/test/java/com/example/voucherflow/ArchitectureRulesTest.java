package com.example.voucherflow;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 通过 ArchUnit 检查控制器、公共层和消息模块的依赖边界。 */
class ArchitectureRulesTest {

    private static final JavaClasses APPLICATION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.voucherflow");

    @Test
    void controllersDoNotDependOnMappers() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().resideInAPackage("com.example.voucherflow..mapper..")
                .check(APPLICATION_CLASSES);
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().areAnnotatedWith(org.springframework.stereotype.Repository.class)
                .check(APPLICATION_CLASSES);
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "org.springframework.transaction..")
                .check(APPLICATION_CLASSES);
    }

    @Test
    void commonDoesNotDependOnOtherApplicationPackages() {
        noClasses().that().resideInAPackage("com.example.voucherflow.common..")
                .should().dependOnClassesThat(resideInAPackage("com.example.voucherflow..")
                        .and(resideOutsideOfPackage("com.example.voucherflow.common..")))
                .check(APPLICATION_CLASSES);
    }

    @Test
    void outboxDoesNotDependOnBusinessImplementation() {
        noClasses().that().resideInAPackage("com.example.voucherflow.outbox..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..campaign..", "..issuance..", "..inventory..", "..fulfillment..",
                        "..redemption..", "..reconciliation..", "..identity..")
                .check(APPLICATION_CLASSES);
    }
}
