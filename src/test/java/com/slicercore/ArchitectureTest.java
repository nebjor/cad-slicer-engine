package com.slicercore;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The module boundaries of the monolith as rules that fail the build. Each top-level package
 * under {@code com.slicercore} is a feature module: {@code quote}, {@code slicer}, {@code upload},
 * {@code order}, {@code payment}, {@code account}, {@code cart}, {@code notification}, {@code admin} and
 * {@code calibration}, plus {@code config}. The order module owns the ports the others implement
 * ({@code PaymentGateway}, {@code CurrentAccount}, {@code RetentionSweep}), so it depends on none of
 * them; the quote module likewise owns {@code SliceRecorder}. Packages sorted by type
 * ({@code controller}, {@code service}, {@code model}) are not allowed to come back.
 */
@AnalyzeClasses(packages = "com.slicercore", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Typed properties in {@code config} name the feature vocabulary they configure
     * ({@code FilamentType}, {@code ColorOption}) while the features read their properties; that
     * pair is shared configuration, not a module cycle, so the edges leaving {@code config} are left out.
     */
    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            slices().matching("com.slicercore.(*)..").should().beFreeOfCycles()
                    .ignoreDependency(resideInAPackage("..config.."), alwaysTrue());

    @ArchTest
    static final ArchRule noPackagesSortedByType =
            noClasses().should().resideInAnyPackage("..controller..", "..service..", "..model..");

    @ArchTest
    static final ArchRule quoteSlicerAndUploadKnowNothingAboutOrdersPaymentsAccountsCartsOrAdmin =
            noClasses().that().resideInAnyPackage("..quote..", "..slicer..", "..upload..")
                    .should().dependOnClassesThat().resideInAnyPackage("..order..", "..payment..", "..admin..",
                            "..account..", "..cart..", "..calibration..", "..notification..");

    /** The slicer is the lower layer: the quote module uses it, never the reverse. */
    @ArchTest
    static final ArchRule slicerKnowsNothingAboutQuotesOrUploads =
            noClasses().that().resideInAPackage("..slicer..")
                    .should().dependOnClassesThat().resideInAnyPackage("..quote..", "..upload..");

    @ArchTest
    static final ArchRule orderModuleDoesNotDependOnAdminOrControllers =
            noClasses().that().resideInAPackage("..order..")
                    .should().dependOnClassesThat().resideInAnyPackage("..admin..", "..account..",
                            "..notification..", "..calibration..");

    /** The calibration slices through the quote module and reads orders; it knows nothing else. */
    @ArchTest
    static final ArchRule calibrationModuleDependsOnQuotesOrdersAndConfigOnly =
            noClasses().that().resideInAPackage("..calibration..")
                    .should().dependOnClassesThat().resideInAnyPackage("..admin..", "..account..",
                            "..notification..", "..payment..");

    /**
     * The cart sits on top of the quote module (model staging) and the order module (file storage,
     * retention port, session port); the account module calls it on deletion, never the reverse.
     */
    @ArchTest
    static final ArchRule cartModuleDependsOnQuotesAndOrdersOnly =
            noClasses().that().resideInAPackage("..cart..")
                    .should().dependOnClassesThat().resideInAnyPackage("..account..", "..admin..",
                            "..notification..", "..payment..", "..calibration..");

    @ArchTest
    static final ArchRule onlyTheAccountModuleCallsTheCart =
            noClasses().that().resideInAnyPackage("..quote..", "..slicer..", "..upload..", "..order..", "..payment..", "..admin..",
                            "..notification..", "..calibration..")
                    .should().dependOnClassesThat().resideInAPackage("..cart..");

    @ArchTest
    static final ArchRule quoteAndSlicerValueTypesDoNotDependOnSpring =
            noClasses().that().resideInAnyPackage("..quote..", "..slicer..").and().areRecords()
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
                    .should().resideInAnyPackage("..quote..", "..upload..", "..order..", "..payment..", "..account..", "..cart..", "..admin..",
                            "..calibration..");
}
