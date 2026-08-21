package com.lrj.benefit.domain;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test
    void domainMustRemainFrameworkFree() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.lrj.benefit.domain");
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.persistence..", "org.apache.kafka..")
                .check(classes);
    }
}
