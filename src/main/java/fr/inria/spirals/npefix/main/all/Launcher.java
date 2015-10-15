package fr.inria.spirals.npefix.main.all;

import fr.inria.spirals.npefix.resi.CallChecker;
import fr.inria.spirals.npefix.resi.Strategy;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import fr.inria.spirals.npefix.transformer.processors.*;
import spoon.SpoonException;
import spoon.SpoonModelBuilder;
import spoon.processing.ProcessingManager;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.AnnotationFilter;
import spoon.support.QueueProcessingManager;
import utils.TestClassesFinder;
import utils.sacha.interfaces.ITestResult;
import utils.sacha.runner.main.TestRunner;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class Launcher {

    private final String[] sourcePath;
    private final String classpath;
    private final String sourceOutput;
    private final String binOutput;
    private final SpoonModelBuilder compiler;
    private spoon.Launcher spoon;

    private final Logger logger = LoggerFactory.getLogger(Launcher.class);

    public Launcher(String[] sourcePath, String sourceOutput, String binOutput, String classpath) {
        this.sourcePath = sourcePath;
        this.classpath = classpath + System.getProperty("java.class.path");
        this.sourceOutput = sourceOutput;
        this.binOutput = binOutput;
        this.compiler = init();
        spoon.setSourceOutputDirectory(sourceOutput);
        spoon.setBinaryOutputDirectory(binOutput);
    }

    /**
     *
     */
    public void instrument() {
        ProcessingManager p = new QueueProcessingManager(spoon.getFactory());

        p.addProcessor(IfSplitter.class.getCanonicalName());
        p.addProcessor(ForceNullInit.class.getCanonicalName());
        p.addProcessor(TargetIfAdder.class.getCanonicalName());
        p.addProcessor(TargetModifier.class.getCanonicalName());
        p.addProcessor(TryRegister.class.getCanonicalName());
        p.addProcessor(VarRetrieveAssign.class.getCanonicalName());
        p.addProcessor(VarRetrieveInit.class.getCanonicalName());
        p.addProcessor(MethodEncapsulation.class.getCanonicalName());
        p.addProcessor(VariableFor.class.getCanonicalName());

        logger.debug("Start code instrumentation");

        ArrayList<CtType<?>> allWithoutTest = getAllClasses();
        p.process(allWithoutTest);

        spoon.prettyprint();

        compiler.compile();
        logger.debug("End code instrumentation");
    }

    /**
     * Get all classes without tests
     * @return
     */
    private ArrayList<CtType<?>> getAllClasses() {
        ArrayList<CtType<?>> allWithoutTest = new ArrayList<>();
        List<CtType<?>> allClasses = spoon.getFactory().Class().getAll();
        for (int i = 0; i < allClasses.size(); i++) {
            CtType<?> ctType = allClasses.get(i);
            if(ctType.getSimpleName().endsWith("Test")) {
                continue;
            }
            // junit 4
            List<CtElement> elements = ctType.getElements(new AnnotationFilter<>(Test.class));
            if(elements.size() > 0) {
                continue;
            }
            // junit 3
            if(ctType.getSuperclass() != null &&
                    ctType.getSuperclass().getQualifiedName().contains("junit")) {
               continue;
            }
            allWithoutTest.add(ctType);
        }
        return allWithoutTest;
    }

    private void copyResources() {
        Collection resources = FileUtils.listFiles(new File(sourceOutput), spoon.RESOURCES_FILE_FILTER, spoon.ALL_DIR_FILTER);
        Iterator var6 = resources.iterator();

        while(var6.hasNext()) {
            Object resource = var6.next();
            String resourceParentPath = ((File)resource).getParent();
            String packageDir = resourceParentPath.substring(new File(sourceOutput).getPath().length());
            packageDir = packageDir.replace("/java", "").replace("/resources", "");
            String targetDirectory = this.binOutput + packageDir;

            try {
                FileUtils.copyFileToDirectory((File) resource, new File(targetDirectory));
            } catch (IOException var12) {
                throw new SpoonException(var12);
            }
        }
    }

    private SpoonModelBuilder init() {
        spoon = new spoon.Launcher();
        for (int i = 0; i < sourcePath.length; i++) {
            String s = sourcePath[i];
            if(s != null) {
                spoon.addInputResource(s);
            }
        }

        SpoonModelBuilder compiler = spoon.getModelBuilder();
        compiler.setSourceClasspath(classpath.split(File.pathSeparator));

        spoon.getEnvironment().setCopyResources(true);
        //spoon.getEnvironment().setAutoImports(true);
        spoon.getEnvironment().setShouldCompile(true);
        spoon.getEnvironment().setGenerateJavadoc(false);
        spoon.getEnvironment().setComplianceLevel(7);

        spoon.buildModel();
        copyResources();
        return compiler;
    }

    public ITestResult runStrategy(Strategy strategy) {
        CallChecker.strat = strategy;

        String[] sourceClasspath = spoon.getModelBuilder().getSourceClasspath();

        URLClassLoader urlClassLoader = getUrlClassLoader(sourceClasspath);

        String[] testsString = new TestClassesFinder().findIn(urlClassLoader, false);
        Class[] tests = filterTest(urlClassLoader, testsString);

        return new TestRunner(tests).run();
    }

    private Class[] filterTest(URLClassLoader urlClassLoader, String[] testsString) {
        List<Class> tests = new ArrayList<>();
        for (int i = 0; i < testsString.length; i++) {
            String s = testsString[i];
            if(!isValidTest(s)) {
                continue;
            }
            try {
                Class<?> aClass = urlClassLoader.loadClass(s);
                tests.add(aClass);
            } catch (ClassNotFoundException e) {
                continue;
            }
        }
        return tests.toArray(new Class[]{});
    }

    public static URLClassLoader getUrlClassLoader(String[] sourceClasspath) {
        ArrayList<URL> uRLClassPath = new ArrayList<>();
        for (int i = 0; i < sourceClasspath.length; i++) {
            String s = sourceClasspath[i];
            try {
                uRLClassPath.add(new File(s).toURL());
            } catch (MalformedURLException e) {
                continue;
            }
        }
        return new URLClassLoader(uRLClassPath.toArray(new URL[]{}));
    }

    private boolean isValidTest(String testName) {
        return spoon.getFactory().Class().get(testName) != null;
    }

    public SpoonModelBuilder getCompiler() {
        return compiler;
    }
}
