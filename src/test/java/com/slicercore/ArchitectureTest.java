package com.slicercore;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The module boundaries of the monolith as rules that fail the build. Each top-level package
 * under {@code com.slicercore} is a feature module: {@code quote}, {@code slicer}, {@code upload},
 * {@code order}, {@code payment}, {@code account}, {@code cart}, {@code notification}, {@code admin} and
 * {@code calibration}, plus {@code config} and the plain-Java leaf {@code catalog}. The order module owns the ports the others implement
 * ({@code PaymentGateway}, {@code CurrentAccount}, {@code RetentionSweep}), so it depends on none of
 * them; the quote module likewise owns {@code SliceRecorder}. Packages sorted by type
 * ({@code controller}, {@code service}, {@code model}) are not allowed to come back.
 */
@AnalyzeClasses(packages = "com.slicercore", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            slices().matching("com.slicercore.(*)..").should().beFreeOfCycles();

    /** Only the top-level type packages; a nested {@code calibration.model} would still be allowed. */
    @ArchTest
    static final ArchRule noPackagesSortedByType =
            noClasses().should().resideInAnyPackage("com.slicercore.controller", "com.slicercore.service",
                    "com.slicercore.model");

    /**
     * {@code catalog} is the shared vocabulary leaf ({@code FilamentType}, {@code PrintQuality},
     * {@code ColorOption}, {@code BoundingBox}) that {@code config} and the features may use.
     */
    @ArchTest
    static final ArchRule catalogDependsOnNothingInTheApplication =
            noClasses().that().resideInAPackage("..catalog..")
                    .should().dependOnClassesThat(resideInTheApplicationOutsideCatalog());

    @ArchTest
    static final ArchRule quoteSlicerAndUploadKnowNothingAboutOrdersPaymentsAccountsCartsOrAdmin =
            noClasses().that().resideInAnyPackage("..quote..", "..slicer..", "..upload..")
                    .should().dependOnClassesThat().resideInAnyPackage("..order..", "..payment..", "..admin..",
                            "..account..", "..cart..", "..calibration..", "..notification..");

    /** Direction: {@code quote} may use {@code slicer} and {@code upload}; they know nothing of it or of each other. */
    @ArchTest
    static final ArchRule slicerDependsOnNeitherQuoteNorUpload =
            noClasses().that().resideInAPackage("..slicer..")
                    .should().dependOnClassesThat().resideInAnyPackage("..quote..", "..upload..");

    @ArchTest
    static final ArchRule uploadDependsOnNeitherQuoteNorSlicer =
            noClasses().that().resideInAPackage("..upload..")
                    .should().dependOnClassesThat().resideInAnyPackage("..quote..", "..slicer..");

    @ArchTest
    static final ArchRule orderModuleDoesNotDependOnAdminAccountNotificationOrCalibration =
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

    /** Value types stay plain Java: nothing in {@code catalog}, and no record, enum or exception elsewhere. */
    @ArchTest
    static final ArchRule catalogDoesNotDependOnSpring =
            noClasses().that().resideInAPackage("..catalog..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule valueTypesOfQuoteSlicerAndUploadDoNotDependOnSpring =
            noClasses().that().resideInAnyPackage("..quote..", "..slicer..", "..upload..")
                    .and(recordsEnumsOrExceptions())
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    /** Controllers are the outermost layer: services never call up into them. */
    @ArchTest
    static final ArchRule nothingDependsOnAController =
            noClasses().that(areNotControllerLayer())
                    .should().dependOnClassesThat(simpleNameEndingWith("Controller"));

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

    private static DescribedPredicate<JavaClass> resideInTheApplicationOutsideCatalog() {
        return DescribedPredicate.describe("reside in the application outside catalog",
                c -> c.getPackageName().startsWith("com.slicercore") && !c.getPackageName().startsWith("com.slicercore.catalog"));
    }

    private static DescribedPredicate<JavaClass> recordsEnumsOrExceptions() {
        return DescribedPredicate.describe("are records, enums or exceptions",
                c -> c.isRecord() || c.isEnum() || c.isAssignableTo(Throwable.class));
    }

    private static DescribedPredicate<JavaClass> areNotControllerLayer() {
        return DescribedPredicate.describe("are not controllers or controller advice",
                c -> !c.getSimpleName().endsWith("Controller") && !c.isAnnotatedWith(RestController.class)
                        && !c.isAnnotatedWith(RestControllerAdvice.class));
    }
}
