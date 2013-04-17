package org.fedora.installer;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.sisu.equinox.EquinoxServiceFactory;
import org.eclipse.sisu.equinox.launching.DefaultEquinoxInstallationDescription;
import org.eclipse.sisu.equinox.launching.EquinoxInstallation;
import org.eclipse.sisu.equinox.launching.EquinoxInstallationDescription;
import org.eclipse.sisu.equinox.launching.EquinoxInstallationFactory;
import org.eclipse.sisu.equinox.launching.EquinoxLauncher;
import org.eclipse.sisu.equinox.launching.internal.EquinoxLaunchConfiguration;
import org.eclipse.tycho.ArtifactKey;
import org.eclipse.tycho.artifacts.TargetPlatform;
import org.eclipse.tycho.core.ee.shared.ExecutionEnvironmentConfigurationStub;
import org.eclipse.tycho.core.osgitools.DefaultArtifactKey;
import org.eclipse.tycho.launching.LaunchConfiguration;
import org.eclipse.tycho.osgi.adapters.MavenLoggerAdapter;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult.Entry;
import org.eclipse.tycho.p2.resolver.facade.P2Resolver;
import org.eclipse.tycho.p2.resolver.facade.P2ResolverFactory;
import org.eclipse.tycho.p2.target.facade.TargetPlatformBuilder;

/**
 * Goal which touches a timestamp file.
 *
 * @goal install
 * 
 */
public class InstallMojo
    extends AbstractMojo
{
    private static final int TIMEOUT_1_HOUR = 3600;

    
    /**
     * @parameter default-value="${project.build.directory}/work"
     */
    private File work;

    /**
     * @parameter expression="${project}"
     */
    private MavenProject project;
    
    /**
     * @parameter sourceRepo="${sourceRepo};
     */
    private File sourceRepo;
   
    /**
     * @parameter targetLocation="${targetLocation};
     */
    private File targetLocation;
    
    
    /** @component */
    private EquinoxInstallationFactory installationFactory;

    /** @component */
    private EquinoxLauncher launcher;

    /** @component */
    private ToolchainManager toolchainManager;

    /** @component */
    private EquinoxServiceFactory equinox;

    /** @component */
    private Logger logger;
    
    /**
     * @parameter expression="${session}"
     * @readonly
     * @required
     */
    private MavenSession session;
    
    /**
     * Execution environment profile name used to resolve dependencies.
     * 
     * @parameter default-value="JavaSE-1.6"
     */
    private String executionEnvironment;
    
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
    	EquinoxInstallation p2DirectorInstallation = constructP2DirectorApp();
    	runEclipse(p2DirectorInstallation);
    }
    
	private EquinoxInstallation constructP2DirectorApp() {
		P2ResolverFactory resolverFactory = equinox
				.getService(P2ResolverFactory.class);
		TargetPlatformBuilder tpBuilder = resolverFactory
				.createTargetPlatformBuilder(new ExecutionEnvironmentConfigurationStub(
						executionEnvironment));

		// we want to resolve from remote repos only
		tpBuilder.setIncludeLocalMavenRepo(true);

		TargetPlatform targetPlatform = tpBuilder.buildTargetPlatform();
		P2Resolver resolver = resolverFactory
				.createResolver(new MavenLoggerAdapter(logger, false));

		Dependency p2DirectorDependency = newBundleDependency("org.eclipse.equinox.p2.repository.tools");
		resolver.addDependency(p2DirectorDependency.getType(),
				p2DirectorDependency.getArtifactId(),
				p2DirectorDependency.getVersion());

		for (Dependency dependency : getDefaultDependencies()) {
			resolver.addDependency(dependency.getType(),
					dependency.getArtifactId(), dependency.getVersion());
		}

		EquinoxInstallationDescription installationDesc = new DefaultEquinoxInstallationDescription();
		for (P2ResolutionResult result : resolver.resolveDependencies(
				targetPlatform, null)) {
			for (Entry entry : result.getArtifacts()) {
				if (ArtifactKey.TYPE_ECLIPSE_PLUGIN.equals(entry.getType())) {
					installationDesc.addBundle(new DefaultArtifactKey(
							ArtifactKey.TYPE_ECLIPSE_PLUGIN, entry.getId(),
							entry.getVersion()), entry.getLocation());
				}
			}
		}
		return installationFactory.createInstallation(installationDesc, work);
	}

	private Dependency newBundleDependency(String bundleId) {
        Dependency dependency = new Dependency();
        dependency.setArtifactId(bundleId);
        dependency.setType(ArtifactKey.TYPE_ECLIPSE_PLUGIN);
        return dependency;
    }

    private List<Dependency> getDefaultDependencies() {
        ArrayList<Dependency> result = new ArrayList<Dependency>();
        result.add(newBundleDependency("org.eclipse.osgi"));
        result.add(newBundleDependency(EquinoxInstallationDescription.EQUINOX_LAUNCHER));
        result.add(newBundleDependency("org.eclipse.core.runtime"));
        return result;
    }

    

    private void runEclipse(EquinoxInstallation runtime) throws MojoExecutionException, MojoFailureException {
        try {
            File workspace = new File(work, "data").getAbsoluteFile();
            FileUtils.deleteDirectory(workspace);
            LaunchConfiguration cli = createCommandLine(runtime);
            getLog().info("Expected eclipse log file: " + new File(workspace, ".metadata/.log").getCanonicalPath());
            int returnCode = launcher.execute(cli, TIMEOUT_1_HOUR);
            if (returnCode != 0) {
                throw new MojoExecutionException("Error while executing platform (return code: " + returnCode + ")");
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Error while executing platform", e);
        }
    }
    

    LaunchConfiguration createCommandLine(EquinoxInstallation runtime) throws MalformedURLException {
        EquinoxLaunchConfiguration cli = new EquinoxLaunchConfiguration(runtime);

        String executable = null;
        Toolchain tc = getToolchain();
        if (tc != null) {
            getLog().info("Toolchain in org.fedora.installer: " + tc);
            executable = tc.findTool("java");
        }
        cli.setJvmExecutable(executable);
        cli.setWorkingDirectory(project.getBasedir());

        addProgramArgs(true, cli, "-install", runtime.getLocation().getAbsolutePath(), "-configuration", new File(work,
                "configuration").getAbsolutePath());

        String appArgLine = "-application org.eclipse.equinox.p2.repository.repo2runnable -source " + sourceRepo + "-target " + targetLocation;
        		
        addProgramArgs(false, cli, appArgLine);

        return cli;
    }

    private void addProgramArgs(boolean escape, EquinoxLaunchConfiguration cli, String... arguments) {
        if (arguments != null) {
            for (String argument : arguments) {
                if (argument != null) {
                    cli.addProgramArguments(escape, argument);
                }
            }
        }
    }

    private Toolchain getToolchain() {
        Toolchain tc = null;
        if (toolchainManager != null) {
            tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
        }
        return tc;
    }
}
