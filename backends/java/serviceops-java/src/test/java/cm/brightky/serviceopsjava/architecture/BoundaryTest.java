package cm.brightky.serviceopsjava.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class BoundaryTest {
    @Test
    void domainAndUseCasesDoNotDependOnSpringOrPersistence() {
        var application = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("cm.brightky.serviceopsjava");
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "..infrastructure..")
                .check(application);
    }
}
