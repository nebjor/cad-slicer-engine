package com.slicercore;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The module boundaries of the monolith as rules that fail the build. Each top-level package
 * under {@code com.slicercore} is a module: quote ({@code service}), {@code order},
 * {@code payment}, {@code account}, {@code cart}, {@code notification}, {@code admin}, {@code calibration},
 * plus {@code model}, {@code config} and {@code controller}. The order module owns the ports
 * the others implement ({@code PaymentGateway}, {@code CurrentAccount}, {@code RetentionSweep}),
 * so it depends on none of them; the quote module likewise owns {@code SliceRecorder}.
 */
@AnalyzeClasses(packages = "com.slicercore", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            slices().matching("com.slicercore.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule quoteModuleKnowsNothingAboutOrdersPaymentsOrAdmin =
            noClasses().that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAnyPackage("..order..", "..payment..", "..admin..",
                            "..controller..", "..calibration..");

    @ArchTest
    static final ArchRule orderModuleDoesNotDependOnAdminOrControllers =
            noClasses().that().resideInAPackage("..order..")
                    .should().dependOnClassesThat().resideInAnyPackage("..admin..", "..controller..", "..account..",
                            "..notification..", "..calibration..");

    /** The calibration slices through the quote module and reads orders; it knows nothing else. */
    @ArchTest
    static final ArchRule calibrationModuleDependsOnQuotesOrdersAndConfigOnly =
            noClasses().that().resideInAPackage("..calibration..")
                    .should().dependOnClassesThat().resideInAnyPackage("..admin..", "..controller..", "..account..",
                            "..notification..", "..payment..");

    /**
     * The cart sits on top of the quote module (model staging) and the order module (file storage,
     * retention port, session port); the account module calls it on deletion, never the reverse.
     */
    @ArchTest
    static final ArchRule cartModuleDependsOnQuotesAndOrdersOnly =
            noClasses().that().resideInAPackage("..cart..")
                    .should().dependOnClassesThat().resideInAnyPackage("..account..", "..admin..", "..controller..",
                            "..notification..", "..payment..", "..calibration..");

    @ArchTest
    static final ArchRule onlyTheAccountModuleCallsTheCart =
            noClasses().that().resideInAnyPackage("..service..", "..order..", "..payment..", "..admin..",
                            "..notification..", "..calibration..")
                    .should().dependOnClassesThat().resideInAPackage("..cart..");

    @ArchTest
    static final ArchRule modelPackageIsPlainJava =
            noClasses().that().resideInAPackage("..model..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories =
            noClasses().that().areAnnotatedWith(RestController.class)
                    .should().dependOnClassesThat().areAssignableTo(Repository.class);

    @ArchTest
    static final ArchRule noFieldInjection =
            noFields().should().beAnnotatedWith(Autowired.class);

    @ArchTest
    static final ArchRule controllersLiveInTheirModules =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().resideInAnyPackage("..controller..", "..order..", "..payment..", "..account..", "..cart..", "..admin..",
                            "..calibration..");
}
