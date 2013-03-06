/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.richfaces.deployment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.faces.webapp.FacesServlet;

import org.apache.commons.io.IOUtils;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptors;
import org.jboss.shrinkwrap.descriptor.api.facesconfig20.FacesConfigVersionType;
import org.jboss.shrinkwrap.descriptor.api.facesconfig20.WebFacesConfigDescriptor;
import org.jboss.shrinkwrap.descriptor.api.webapp30.WebAppDescriptor;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.richfaces.arquillian.configuration.FundamentalTestConfiguration;
import org.richfaces.arquillian.configuration.FundamentalTestConfigurationContext;

import com.google.common.base.Function;
import com.google.common.collect.Sets;

/**
 * Provides base for all test deployments
 *
 * @author Lukas Fryc
 */
public class Deployment {

    private final Logger log = Logger.getLogger(Deployment.class.getName());
    private final FundamentalTestConfiguration configuration = FundamentalTestConfigurationContext.getProxy();
    private final File cacheDir = new File("target/shrinkwrap-resolver-cache/");

    private WebArchive archive;

    private WebFacesConfigDescriptor facesConfig;
    private WebAppDescriptor webXml;

    private Set<String> mavenDependencies = Sets.newHashSet();
    private Set<String> excludedMavenDependencies = Sets.newHashSet();

    /**
     * Constructs base deployment with:
     *
     * <ul>
     * <li>archive named by test case</li>
     * <li>empty faces-config.xml</li>
     * <li>web.xml with default FacesServlet mapping, welcome file index.xhtml and disabled some features which are not
     * necessary by default and Development stage turned on</li>
     * </ul>
     *
     * @param testClass
     */
    protected Deployment(Class<?> testClass) {
        if (testClass != null) {
            this.archive = ShrinkWrap.create(WebArchive.class, testClass.getSimpleName() + ".war");
        } else {
            this.archive = ShrinkWrap.create(WebArchive.class);
        }

        this.facesConfig = Descriptors.create(WebFacesConfigDescriptor.class).version(FacesConfigVersionType._2_0);

        this.webXml = Descriptors.create(WebAppDescriptor.class)
                .version("3.0")
                .addNamespace("xmlns:web", "http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd")
                .getOrCreateWelcomeFileList()
                    .welcomeFile("faces/index.xhtml")
                .up()
                .getOrCreateContextParam()
                    .paramName("org.richfaces.enableControlSkinning")
                    .paramValue("false")
                .up()
                .getOrCreateContextParam()
                    .paramName("javax.faces.PROJECT_STAGE")
                    .paramValue("Development")
                .up()
                .getOrCreateServlet()
                    .servletName(FacesServlet.class.getSimpleName())
                    .servletClass(FacesServlet.class.getName())
                    .loadOnStartup(1)
                .up()
                .getOrCreateServletMapping()
                    .servletName(FacesServlet.class.getSimpleName())
                    .urlPattern("*.jsf")
                .up()
                .getOrCreateServletMapping()
                    .servletName(FacesServlet.class.getSimpleName())
                    .urlPattern("/faces/*")
                .up();

        // TODO versions have to be loaded from POM
        addMavenDependency("com.google.guava:guava", "net.sourceforge.cssparser:cssparser");

        // Servlet container setup
        if (configuration.servletContainerSetup()) {
            log.info("Adding Servlet Container extensions for JSF");
            withServletContainerSetup();
        }

        if (!configuration.isCurrentRichFacesVersion()) {
            log.info("Running test against RichFaces version: " + configuration.getRichFacesVersion());
        }
    }

    /**
     * Provides {@link WebArchive} available for modifications
     */
    public WebArchive archive() {
        return archive;
    }

    /**
     * Returns the final testable archive - packages all the resources which were configured separately
     */
    public WebArchive getFinalArchive() {
        WebArchive finalArchive = archive
                .addAsWebInfResource(new StringAsset(facesConfig.exportAsString()), "faces-config.xml")
                .addAsWebInfResource(new StringAsset(webXml.exportAsString()), "web.xml");



        // add library dependencies
        exportMavenDependenciesToArchive(finalArchive);

        return finalArchive;
    }

    /**
     * Allows to modify contents of faces-config.xml.
     *
     * Takes function which transforms original faces-config.xml and returns modified one
     */
    public void facesConfig(Function<WebFacesConfigDescriptor, WebFacesConfigDescriptor> transform) {
        this.facesConfig = transform.apply(this.facesConfig);
    }

    /**
     * Allows to modify contents of web.xml.
     *
     * Takes function which transforms original web.xml and returns modified one
     */
    public void webXml(Function<WebAppDescriptor, WebAppDescriptor> transform) {
        this.webXml = transform.apply(this.webXml);
    }

    /**
     * Resolves maven dependencies, either by {@link MavenDependencyResolver} or from file cache
     */
    private void exportMavenDependenciesToArchive(WebArchive finalArchive) {

        Set<File> jarFiles = Sets.newHashSet();

        for (String dependency : mavenDependencies) {
            try {
                File dependencyDir = new File(cacheDir, dependency);

                if (!dependencyDir.exists()) {
                    resolveMavenDependency(dependency, dependencyDir);
                } else if (dependencyDirIsStale(dependencyDir)) {
                    cleanDependencyDir(dependencyDir);
                    resolveMavenDependency(dependency, dependencyDir);
                }

                File[] listFiles = dependencyDir.listFiles(new FilenameFilter() {

                    @Override
                    public boolean accept(File dir, String name) {
                        return name.endsWith(".jar");
                    }
                });

                jarFiles.addAll(Arrays.asList(listFiles));
            } catch (Exception e) {
                throw new IllegalStateException("Can't resolve maven dependency: " + dependency);
            }
        }

        File[] files = jarFiles.toArray(new File[jarFiles.size()]);
        finalArchive.addAsLibraries(files);
    }

    private boolean dependencyDirIsStale(File dir) {
        long lastModified = dir.lastModified();
        long expires = lastModified + 720000;

        return System.currentTimeMillis() > expires;
    }

    private void cleanDependencyDir(File dir) {
        for (File file : dir.listFiles()) {
            file.delete();
        }
        dir.delete();
    }

    /**
     * Adds maven artifact as library dependency
     */
    public Deployment addMavenDependency(String... dependencies) {
        mavenDependencies.addAll(Arrays.asList(dependencies));
        return this;
    }

    /**
     * Adds patters for Maven library dependency exclusion
     */
    public Deployment excludeMavenDependency(String... dependencies) {
        excludedMavenDependencies.addAll(Arrays.asList(dependencies));
        return this;
    }

    /**
     * Adds dependencies which are necessary to deploy onto Servlet containers (Tomcat, Jetty)
     */
    private Deployment withServletContainerSetup() {
        addMavenDependency(configuration.getJsfImplementation());

        addMavenDependency("org.jboss.weld.servlet:weld-servlet");
        excludeMavenDependency("slf4j-api");

        addMavenDependency("org.jboss.el:jboss-el");
        excludeMavenDependency("el-api");

        addMavenDependency("javax.annotation:jsr250-api:1.0");
        addMavenDependency("javax.servlet:jstl:1.2");



        webXml(new Function<WebAppDescriptor, WebAppDescriptor>() {
            @Override
            public WebAppDescriptor apply(@Nullable WebAppDescriptor input) {

                input
                    .createListener()
                        .listenerClass("org.jboss.weld.environment.servlet.Listener")
                        .up();

                return input;
            }
        });

        return this;
    }

    /**
     * Resolves Maven dependency and writes it to the cache, so it can be reused next run
     */
    private void resolveMavenDependency(String missingDependency, File dir) {

        JavaArchive[] dependencies = Maven.resolver()
                .loadPomFromFile("pom.xml").resolve(missingDependency).withTransitivity().as(JavaArchive.class);

        for (JavaArchive archive : dependencies) {
            dir.mkdirs();
            if (mavenDependencyExcluded(archive.getName())) {
                continue;
            }
            File outputFile = new File(dir, archive.getName());
            InputStream zipStream = archive.as(ZipExporter.class).exportAsInputStream();
            try {
                IOUtils.copy(zipStream, new FileOutputStream(outputFile));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private boolean mavenDependencyExcluded(String archiveName) {
        for (String exclude : excludedMavenDependencies) {
            if (archiveName.contains(exclude)) {
                return true;
            }
        }
        return false;
    }
}
