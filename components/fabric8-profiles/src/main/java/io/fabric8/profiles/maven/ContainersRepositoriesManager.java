package io.fabric8.profiles.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import io.fabric8.profiles.ProfilesHelpers;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * After all containers are generated, push generated container source to Git repos.
 */
@Mojo(name = "update", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresProject = true,
    defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class ContainersRepositoriesManager extends AbstractProfilesMojo {

    protected static final String GIT_REMOTE_URI_PROPERTY = "gitRemoteUri";
    protected static final String GIT_REMOTE_NAME_PROPERTY = "gitRemoteName";
    protected static final String GIT_REMOTE_URI_PATTERN_PROPERTY = "gitRemoteUriPattern";

    private String currentVersion;
    private ObjectId currentCommitId;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        // initialize inherited fields
        super.execute();

        // get current repository branch version to compare against remotes
        try (final Git sourceRepo = Git.open(sourceDirectory)) {

            currentVersion = sourceRepo.getRepository().getBranch();

            Iterable<RevCommit> commitLog = sourceRepo.log().setMaxCount(1).call();
            for (RevCommit revCommit : commitLog) {
                currentCommitId = revCommit.getId();
            }

            // list all containers, and update under targetDirectory
            final Path target = Paths.get(targetDirectory.getAbsolutePath());
            final List<Path> names = Files.list(configs.resolve("containers"))
                .filter(p -> p.getFileName().toString().endsWith(".cfg"))
                .collect(Collectors.toList());

            // TODO handle container deletes

            // generate all current containers
            for (Path name : names) {
                manageContainer(target, name);
            }

        } catch (IOException e) {
            throwMojoException("Error reading Profiles Git repo", sourceDirectory, e);
        } catch (NoHeadException e) {
            throwMojoException("Error reading Profiles Git repo", sourceDirectory, e);
        } catch (GitAPIException e) {
            throwMojoException("Error reading Profiles Git repo", sourceDirectory, e);
        }
    }

    /**
     * Allow overriding to do something sophisticated, for example,
     * check what changed in git log from last build to only build containers whose profiles changed.
     */
    protected void manageContainer(Path target, Path configFile) throws MojoExecutionException {

        // read container config
        Properties config = null;
        try {
            config = ProfilesHelpers.readPropertiesFile(configFile);
        } catch (IOException e) {
            throwMojoException("Error reading container configuration", configFile, e);
        }

        final String configFileName = configFile.getFileName().toString();
        final String name = configFileName.substring(0, configFileName.lastIndexOf('.'));

        // make sure container dir exists
        final Path containerDir = target.resolve(name);
        if (!Files.isDirectory(containerDir)) {
            throw new MojoExecutionException("Missing generated container " + containerDir);
        }

        // get or create remote repo URL
        String remoteUri = config.getProperty(GIT_REMOTE_URI_PROPERTY);
        if (remoteUri == null || remoteUri.isEmpty()) {
            remoteUri = getRemoteUri(name);
        }

        // try to clone remote repo in temp dir
        String remote = config.getProperty(GIT_REMOTE_NAME_PROPERTY, Constants.DEFAULT_REMOTE_NAME);
        Path tempDirectory = null;
        try {
            tempDirectory = Files.createTempDirectory(containerDir, "cloned-remote-");
        } catch (IOException e) {
            throwMojoException("Error cloning ", remoteUri, e);
        }
        try (Git clonedRepo = Git.cloneRepository()
            .setDirectory(tempDirectory.toFile())
            .setBranch(currentVersion)
            .setRemote(remote)
            .setURI(remoteUri)
            .call()) {

            // handle missing remote branch
            if (!clonedRepo.getRepository().getBranch().equals(currentVersion)) {
                clonedRepo.branchCreate()
                    .setName(currentVersion)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
            }

            // move .git dir to parent and drop old source altogether
            // TODO things like .gitignore, etc. need to be handled, perhaps through Profiles??
            Files.move(tempDirectory.resolve(".git"), containerDir.resolve(".git"));

        } catch (InvalidRemoteException e) {
            // TODO handle creating new remote repo in github, gogs, etc. using fabric8 devops connector
            if (e.getCause() instanceof NoRemoteRepositoryException) {
                throwMojoException("Remote repo creation not supported for container", name, e);
            }
            throwMojoException("Error cloning ", remoteUri, e);
        } catch (GitAPIException e) {
            throwMojoException("Error cloning ", remoteUri, e);
        } catch (IOException e) {
            throwMojoException("Error copying files from ", remoteUri, e);
        } finally {
            // cleanup tempDirectory
            try {
                ProfilesHelpers.deleteDirectory(tempDirectory);
            } catch (IOException e) {
                // ignore
            }
        }

        try (Git containerRepo = Git.open(containerDir.toFile())) {

            // diff with remote
            List<DiffEntry> diffEntries = containerRepo.diff().call();
            if (!diffEntries.isEmpty()) {

                // add all changes
                containerRepo.add().addFilepattern(".").call();

                // with latest Profile repo commit ID in message
                // TODO provide other identity properties
                containerRepo.commit().setMessage("Container updated for commit " + currentCommitId.name()).call();

                // push to remote
                containerRepo.push().setRemote(remote).call();
            } else {
                log.debug("No changes to container" + name);
            }

        } catch (GitAPIException e) {
            throwMojoException("Error processing container Git repo ", containerDir, e);
        } catch (IOException e) {
            throwMojoException("Error reading container Git repo ", containerDir, e);
        }
    }

    private String getRemoteUri(String name) throws MojoExecutionException {
        String gitRemotePattern = profilesProperties.getProperty(GIT_REMOTE_URI_PATTERN_PROPERTY);
        if (gitRemotePattern == null) {
            throw new MojoExecutionException("Missing property " + GIT_REMOTE_URI_PATTERN_PROPERTY);
        }
        return gitRemotePattern.replace("${name}", name);
    }

    private void throwMojoException(String message, Object target, Exception e) throws MojoExecutionException {
        throw new MojoExecutionException(String.format("%s %s : %s", message, target, e.getMessage()), e);
    }
}
