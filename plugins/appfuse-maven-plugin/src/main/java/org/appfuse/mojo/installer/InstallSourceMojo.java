package org.appfuse.mojo.installer;

import org.apache.commons.beanutils.BeanComparator;
import org.apache.commons.io.FileUtils;
import org.apache.maven.embedder.MavenEmbedder;
import org.apache.maven.embedder.MavenEmbedderConsoleLogger;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Get;
import org.apache.tools.ant.taskdefs.LoadFile;
import org.apache.tools.ant.taskdefs.optional.ReplaceRegExp;
import org.appfuse.tool.SubversionUtils;
import org.codehaus.plexus.components.interactivity.Prompter;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;


/**
 * This mojo is used to "install" source artifacts from Subversion into an AppFuse project.
 *
 * @author <a href="mailto:matt@raibledesigns.com">Matt Raible</a>
 * @goal full-source
 */
public class InstallSourceMojo extends AbstractMojo {
    private static final String APPFUSE_GROUP_ID = "org.appfuse";
    private static final String FILE_SEP = System.getProperty("file.separator");
    private String daoFramework;
    private String webFramework;
    Project antProject = AntUtils.createProject();
    Properties appfuseProperties;

    /**
     * The path where the files from SVN will be placed. This is intentionally set to "src" since that's the default
     * src directory used for exporting AppFuse artifacts.
     *
     * @parameter expression="${appfuse.destinationDirectory}" default-value="${basedir}/src"
     */
    private String destinationDirectory;

    /**
     * User prompter to get input from user executing mojo.
     *
     * @component
     */
    protected Prompter prompter;

    /**
     * The directory containing the source code.
     *
     * @parameter expression="${appfuse.trunk}" default-value="https://appfuse.dev.java.net/svn/appfuse/"
     */
    private String trunk;

    /**
     * The tag containing the source code - defaults to '/trunk', but you may want to set it to '/tags/TAGNAME'
     *
     * @parameter expression="${appfuse.tag}" default-value="trunk/"
     */
    private String tag;

    /**
     * <i>Maven Internal</i>: Project to interact with.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     * @noinspection UnusedDeclaration
     */
    private MavenProject project;

    public void execute() throws MojoExecutionException, MojoFailureException {
        // todo: get this working from the top-level directory - only works with basic project for now
        if (!project.getPackaging().equalsIgnoreCase("war")) {
            String errorMsg = "This plugin can currently only be run from an AppFuse web project (packaging == 'war').";

            //getLog().error(errorMsg);
            throw new MojoFailureException(errorMsg);
        }

        // If appfuse.version is specified as a property, and not a SNAPSHOT, use it for the tag
        String appfuseVersion = project.getProperties().getProperty("appfuse.version");

        if ((appfuseVersion != null) && !appfuseVersion.endsWith("SNAPSHOT") && tag.equals("trunk/")) {
            tag = "tags/APPFUSE_" + appfuseVersion.toUpperCase().replaceAll("-", "_") + "/";
        }

        daoFramework = project.getProperties().getProperty("dao.framework");

        if (daoFramework == null) {
            log("No dao.framework property specified, defaulting to 'hibernate'");
        }

        webFramework = project.getProperties().getProperty("web.framework");

        // install dao and manager source if modular/core or war writer/o parent (basic)
        if (project.getPackaging().equals("jar") || (project.getPackaging().equals("war") && (project.getParent() == null))) {
            log("Installing source from data modules...");
            // export data-common
            export("data/common/src");

            // export persistence framework
            export("data/" + daoFramework + "/src");

            // if jpa or hibernate, remove duplicate file in test directory
            if (daoFramework.equalsIgnoreCase("hibernate")) {
                File duplicateFile = new File(getFilePath("src/test/resources/hibernate.cfg.xml"));

                if (duplicateFile.exists()) {
                    //log("Deleting duplicate hibernate.cfg.xml from src/test/resources...");

                    try {
                        FileUtils.forceDeleteOnExit(duplicateFile);
                    } catch (IOException io) {
                        getLog().error("Failed to delete src/test/resources/hibernate.cfg.xml, please delete manually.");
                    }
                }
            } else if (daoFramework.equalsIgnoreCase("jpa-hibernate")) {
                File duplicateFile = new File(getFilePath("src/test/resources/META-INF"));

                if (duplicateFile.exists()) {
                    log("Deleting duplicate persistence.xml from src/test/resources/META-INF...");

                    try {
                        // For some reason, this just deletes persistence.xml, not the META-INF directory.
                        // I tried FileUtils.deleteDirectory(duplicateFile), but no dice.
                        FileUtils.forceDeleteOnExit(duplicateFile);
                    } catch (IOException io) {
                        getLog().error("Failed to delete src/test/resources/META-INF/persistence.xml, please delete manually.");
                    }
                }
            }

            // export service module
            log("Installing source from service module...");
            export("service/src");

            // add dependencies from appfuse-service to pom.xml
        }

        if (project.getPackaging().equalsIgnoreCase("war")) {
            if (webFramework == null) {
                getLog().error("The web.framework property is not specified - please modify your pom.xml to add " +
                    " this property. For example: <web.framework>struts</web.framework>.");
                throw new MojoExecutionException("No web.framework property specified, please modify pom.xml to add it.");
            }

            // export web-common
            log("Installing source from web-common module...");
            export("web/common/src");

            // export web framework
            log("Installing source from " + webFramework + " module...");
            export("web/" + webFramework + "/src");
        }

        log("Source successfully exported, modifying pom.xml...");
        removeWarpathPlugin(new File("pom.xml"));

        List dependencies = project.getOriginalModel().getDependencies();
        List<Dependency> newDependencies = new ArrayList<Dependency>();

        // remove all appfuse dependencies
        for (Object dependency : dependencies) {
            Dependency dep = (Dependency) dependency;

            if (!dep.getGroupId().equals(APPFUSE_GROUP_ID)) {
                newDependencies.add(dep);
            }
        }

        // add dependencies from root appfuse pom
        newDependencies = addModuleDependencies(newDependencies, "root", "");

        // Add dependencies from appfuse-${dao.framework}
        newDependencies = addModuleDependencies(newDependencies, daoFramework, "data/" + daoFramework);

        // Add dependencies from appfuse-service
        newDependencies = addModuleDependencies(newDependencies, "service", "service");

        // Add dependencies from appfuse-common-web
        newDependencies = addModuleDependencies(newDependencies, "web-common", "web/common");

        // Add dependencies from appfuse-${web.framework}
        newDependencies = addModuleDependencies(newDependencies, webFramework, "web/" + webFramework, true);

        // Change spring-mock and jmock dependencies to use <optional>true</option> instead of <scope>test</scope>.
        // This is necessary because Base*TestCase classes are in src/main/java. If we move these classes to their
        // own test module, this will no longer be necessary. For the first version of this mojo, it seems easier
        // to follow the convention used in AppFuse rather than creating a test module and changing all modules to
        // depend on it.

        // create properties based on dependencies while we're at it
        Set<String> projectProperties = new TreeSet<String>();

        for (Dependency dep : newDependencies) {
            if (dep.getArtifactId().equals("spring-mock") || dep.getArtifactId().equals("jmock") ||
                dep.getArtifactId().equals("junit") || dep.getArtifactId().equals("shale-test")) {
                dep.setOptional(true);
                dep.setScope(null);
            }
            String version = dep.getVersion();
            // trim off ${}
            if (version.startsWith("${")) {
                version = version.substring(2);
            }

            if (version.endsWith("}")) {
                version = version.substring(0, version.length() - 1);
            }
            projectProperties.add(version);
        }

        Collections.sort(newDependencies, new BeanComparator("groupId"));

        project.getOriginalModel().setDependencies(newDependencies);

        Properties currentProperties = project.getOriginalModel().getProperties();

        Set<String> currentKeys = new LinkedHashSet<String>();
        for (Object key : currentProperties.keySet()) {
            currentKeys.add((String) key);
        }

        StringBuffer sortedProperties = new StringBuffer();
        
        for (String key : projectProperties) {
            // don't add property if it already exists in project
            if (!currentKeys.contains(key)) {
                String value = appfuseProperties.getProperty(key);

                if (value.contains("&amp;")) {
                    value = "<![CDATA[" + value + "]]>";
                }

                sortedProperties.append("        <").append(key).append(">")
                        .append(value).append("</").append(key).append(">" + "\n");
            }
        }

        StringWriter writer = new StringWriter();

        try {
            project.writeOriginalModel(writer);

            File pom = new File("pom-fullsource.xml");

            if (pom.exists()) {
                pom.delete();
            }

            FileWriter fw = new FileWriter(pom);
            fw.write(writer.toString());
            fw.flush();
            fw.close();
        } catch (IOException ex) {
            getLog().error("Unable to create pom-fullsource.xml: " + ex.getMessage(), ex);
            throw new MojoFailureException(ex.getMessage());
        }

        log("Updated dependencies in pom.xml...");

        // I tried to use regex here, but couldn't get it to work - going with the old fashioned way instead
        String pomXml = writer.toString();
        int startTag = pomXml.indexOf("\n  <dependencies>");

        String dependencyXml = pomXml.substring(startTag, pomXml.indexOf("</dependencies>", startTag));
        // change 2 spaces to 4
        dependencyXml = dependencyXml.replaceAll("  ", "    ");
        dependencyXml = "\n    <!-- Dependencies calculated by AppFuse when running full-source plugin -->" + dependencyXml;

        try {
            String originalPom = FileUtils.readFileToString(new File("pom.xml"));
            startTag = originalPom.indexOf("\n    <dependencies>");

            StringBuffer sb = new StringBuffer();
            sb.append(originalPom.substring(0, startTag));
            sb.append(dependencyXml);
            sb.append(originalPom.substring(originalPom.indexOf("</dependencies>", startTag)));

            // add new properties
            String pomWithProperties = sb.toString().replace("</properties>\n</project>",
                    "\n        <!-- Properties calculated by AppFuse when running full-source plugin -->\n" + sortedProperties + "    </properties>\n</project>");

            pomWithProperties = pomWithProperties.replaceAll("<amp.fullSource>false</amp.fullSource>", "<amp.fullSource>true</amp.fullSource>");

            String os = System.getProperty("os.name");

            if (os.startsWith("Linux") || os.startsWith("Mac")) {
                // remove the \r returns
                pomWithProperties = pomWithProperties.replaceAll("\r", "");
            }

            FileUtils.writeStringToFile(new File("pom.xml"), pomWithProperties); // was pomWithProperties
        } catch (IOException ex) {
            getLog().error("Unable to write to pom.xml: " + ex.getMessage(), ex);
            throw new MojoFailureException(ex.getMessage());
        }

        boolean renamePackages = true;
        if (System.getProperty("renamePackages") != null) {
            renamePackages = Boolean.valueOf(System.getProperty("renamePackages"));
        }

        if (renamePackages) {
            log("Renaming packages to '" + project.getGroupId() + "'...");
            org.appfuse.tool.FileUtils renamePackagesTool = new org.appfuse.tool.FileUtils(project.getGroupId());
            renamePackagesTool.execute();
        }

        // todo: gather and add repositories from appfuse projects
        // should work for now since most artifacts are in static.appfuse.org/repository

        // cleanup so user isn't aware that files were created
        File pom = new File("pom-fullsource.xml");

        if (pom.exists()) {
            pom.delete();
        }
    }

    private String getFilePath(String s) {
        s = s.replace("/", FILE_SEP);

        return s;
    }

    private void export(String url) throws MojoExecutionException {
        SubversionUtils svn = new SubversionUtils(trunk + tag + url, destinationDirectory);

        try {
            svn.export();
        } catch (SVNException e) {
            SVNErrorMessage err = e.getErrorMessage();

            /*
             * Display all tree of error messages.
             * Utility method SVNErrorMessage.getFullMessage() may be used instead of the loop.
             */
            while (err != null) {
                getLog()
                    .error(err.getErrorCode().getCode() + " : " +
                    err.getMessage());
                err = err.getChildErrorMessage();
            }

            throw new MojoExecutionException(e.getMessage());
        }
    }

    private String getDaoFramework() {
        if (daoFramework.equalsIgnoreCase("jpa-hibernate")) {
            return "jpa";
        } else {
            return daoFramework;
        }
    }

    private void log(String msg) {
        getLog().info("[AppFuse] " + msg);
    }

    private void removeWarpathPlugin(File pom) {
        log("Removing maven-warpath-plugin...");

        Project antProject = AntUtils.createProject();
        ReplaceRegExp regExpTask = (ReplaceRegExp) antProject.createTask("replaceregexp");
        regExpTask.setFile(pom);
        regExpTask.setMatch("\\s*<plugin>\\s*<groupId>org.appfuse</groupId>(?s:.)*?<artifactId>maven-warpath-plugin</artifactId>(?s:.)*?</plugin>");
        regExpTask.setReplace("");
        regExpTask.setFlags("g");
        regExpTask.execute();

        // remove any warpath dependencies as well
        ReplaceRegExp regExpTask2 = (ReplaceRegExp) antProject.createTask("replaceregexp");
        regExpTask2.setFile(pom);
        regExpTask2.setMatch("\\s*<dependency>\\s*<groupId>\\$\\{pom\\.groupId\\}</groupId>(?s:.)*?<type>warpath</type>(?s:.)*?</dependency>");
        regExpTask2.setReplace("");
        regExpTask2.setFlags("g");
        regExpTask2.execute();

        ReplaceRegExp regExpTask3 = (ReplaceRegExp) antProject.createTask("replaceregexp");
        regExpTask3.setFile(pom);
        regExpTask3.setMatch("\\s*<dependency>\\s*<groupId>org.appfuse</groupId>(?s:.)*?<type>warpath</type>(?s:.)*?</dependency>");
        regExpTask3.setReplace("");
        regExpTask3.setFlags("g");
        regExpTask3.execute();
    }

    // Convenience method that doesn't remove warpath plugin
    private List<Dependency> addModuleDependencies(List<Dependency> dependencies, String moduleName, String moduleLocation) {
        return addModuleDependencies(dependencies, moduleName, moduleLocation, false);
    }

    private List<Dependency> addModuleDependencies(List<Dependency> dependencies, String moduleName, String moduleLocation, boolean removeWarpath) {
        log("Adding dependencies from " + moduleName + " module...");

        // Read dependencies from module's pom.xml
        URL pomLocation = null;
        File newDir = new File("target", "appfuse-" + moduleName);

        if (!newDir.exists()) {
            newDir.mkdirs();
        }

        File pom = new File("target/appfuse-" + moduleName + "/pom.xml");

        try {
            pomLocation = new URL(trunk + tag + moduleLocation + "/pom.xml");
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

        Get get = (Get) AntUtils.createProject().createTask("get");
        get.setSrc(pomLocation);
        get.setDest(pom);
        get.setUsername("guest");
        get.setPassword("");
        get.execute();

        if (removeWarpath) {
            this.removeWarpathPlugin(pom);
        }

        MavenProject p = createProjectFromPom(pom);

        List moduleDependencies = p.getOriginalModel().getDependencies();

        // set the properties for appfuse if root module
        if (moduleName.equalsIgnoreCase("root")) {
            appfuseProperties = p.getOriginalModel().getProperties();
        }

        // create a list of artifactIds to check for duplicates (there's no equals() on Dependency)
        Set<String> artifactIds = new LinkedHashSet<String>();

        for (Dependency dep : dependencies) {
            artifactIds.add(dep.getArtifactId());
        }

        // add all non-appfuse dependencies
        for (Object moduleDependency : moduleDependencies) {
            Dependency dep = (Dependency) moduleDependency;

            if (!artifactIds.contains(dep.getArtifactId()) &&
                    !dep.getArtifactId().contains("appfuse")) {
                dependencies.add(dep);
            }
        }

        return dependencies;
    }

    private MavenProject createProjectFromPom(File pom) {
        MavenEmbedder maven = new MavenEmbedder();
        maven.setOffline(true);
        maven.setClassLoader(Thread.currentThread().getContextClassLoader());
        maven.setLogger(new MavenEmbedderConsoleLogger());

        MavenProject p = null;

        try {
            maven.start();
            p = maven.readProjectWithDependencies(pom);
            maven.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return p;
    }

    /**
     * This method will create an ANT based LoadFile task based on an infile and a property name.
     * The property will be loaded with the infile for use later by the Replace task.
     *
     * @param inFile   The file to process
     * @param propName the name to assign it to
     * @return The ANT LoadFile task that loads a property with a file
     */
    protected LoadFile createLoadFileTask(String inFile, String propName) {
        LoadFile loadFileTask = (LoadFile) antProject.createTask("loadfile");
        loadFileTask.init();
        loadFileTask.setProperty(propName);
        loadFileTask.setSrcFile(new File(inFile));

        return loadFileTask;
    }
}
