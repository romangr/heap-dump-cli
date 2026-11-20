package hd.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The engine boundary: only engine-mat may touch Eclipse MAT / Eclipse platform classes, and nothing above the
 * engine API may depend on a concrete engine. Replacing the core must not require touching anything else.
 */
class ArchitectureTest {

    static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("hd");
    }

    @Test
    void onlyEngineMatUsesMat() {
        noClasses().that().resideOutsideOfPackage("hd.engine.mat..")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse..", "org.osgi..")
                .because("MAT is an implementation detail of the mat engine")
                .check(classes);
    }

    @Test
    void nothingDependsOnTheMatEngine() {
        noClasses().that().resideOutsideOfPackage("hd.engine.mat..")
                .should().dependOnClassesThat().resideInAPackage("hd.engine.mat..")
                .because("engines are discovered through ServiceLoader, never referenced directly")
                .check(classes);
    }

    @Test
    void engineApiIsSelfContained() {
        noClasses().that().resideInAPackage("hd.engine.api..")
                .should().dependOnClassesThat().resideInAnyPackage("hd.analysis..", "hd.daemon..", "hd.cli..",
                        "hd.protocol..", "hd.hprof..", "com.fasterxml..")
                .check(classes);
    }

    @Test
    void analysisDoesNotKnowTransportOrDaemon() {
        noClasses().that().resideInAPackage("hd.analysis..")
                .should().dependOnClassesThat().resideInAnyPackage("hd.daemon..", "hd.cli..", "hd.protocol..", "com.fasterxml..")
                .check(classes);
    }

    @Test
    void cliDoesNotDependOnDaemonOrAnalysisCode() {
        noClasses().that().resideInAPackage("hd.cli..")
                .should().dependOnClassesThat().resideInAnyPackage("hd.daemon..", "hd.analysis..", "hd.engine..", "hd.hprof..")
                .because("the client is a thin messenger; it starts the daemon by class name only")
                .check(classes);
    }
}
