/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.git;

import io.syndesis.core.SyndesisServerException;
import org.eclipse.egit.github.core.User;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Service
@ConditionalOnProperty(value = "git.enabled", matchIfMissing = true, havingValue = "true")
public class GitWorkflow {

    private static final Logger LOG = LoggerFactory.getLogger(GitWorkflow.class);

    private final GitProperties gitProperties;

    public GitWorkflow(GitProperties gitProperties) {
        this.gitProperties = gitProperties;
    }

    /**
     * Creates a new remote git repository and does the initial commit&push of all the project files
     * the files to it.
     *
     * @param remoteGitRepoHttpUrl- the HTML (not ssh) url to a git repository
     * @param repoName              - the name of the git repository
     * @param author                author
     * @param message-              commit message
     * @param files-                map of file paths along with their content
     * @param credentials-          Git credentials, for example username/password, authToken, personal access token
     */
    public void createFiles(String remoteGitRepoHttpUrl, String repoName, User author, String message, Map<String, byte[]> files,
                            UsernamePasswordCredentialsProvider credentials) {

        try {
            // create temporary directory
            Path workingDir = Files.createTempDirectory(Paths.get(gitProperties.getLocalGitRepoPath()), repoName);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Created temporary directory {}", workingDir.toString());
            }

            // git init
            Git git = Git.init().setDirectory(workingDir.toFile()).call();
            writeFiles(workingDir, files);

            RemoteAddCommand remoteAddCommand = git.remoteAdd();
            remoteAddCommand.setName("origin");
            remoteAddCommand.setUri(new URIish(remoteGitRepoHttpUrl));
            remoteAddCommand.call();

            commitAndPush(git, authorName(author), author.getEmail(), message, credentials);
            removeWorkingDir(workingDir);
        } catch (IOException | GitAPIException | URISyntaxException e) {
            throw SyndesisServerException.launderThrowable(e);
        }
    }

    /**
     * Updates an existing git repository with the current version of project files.
     *
     * @param remoteGitRepoHttpUrl- the HTML (not ssh) url to a git repository
     * @param repoName              - the name of the git repository
     * @param author                author
     * @param message-              commit message
     * @param files-                map of file paths along with their content
     * @param credentials-          Git credentials, for example username/password, authToken, personal access token
     */
    public void updateFiles(String remoteGitRepoHttpUrl, String repoName, User author, String message, Map<String, byte[]> files,
                                 UsernamePasswordCredentialsProvider credentials) {

        // create temporary directory
        try {
            Path workingDir = Files.createTempDirectory(Paths.get(gitProperties.getLocalGitRepoPath()), repoName);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Created temporary directory {}", workingDir.toString());
            }

            // git clone
            Git git = Git.cloneRepository().setDirectory(workingDir.toFile()).setURI(remoteGitRepoHttpUrl).call();
            writeFiles(workingDir, files);

            commitAndPush(git, authorName(author), author.getEmail(), message, credentials);
            removeWorkingDir(workingDir);
        } catch (IOException | GitAPIException e) {
            throw SyndesisServerException.launderThrowable(e);
        }
    }

    private String authorName(User author) {
        if (author.getName() != null) {
            return author.getName();
        }
        return author.getLogin();
    }

    private void removeWorkingDir(Path workingDir) throws IOException {
        // cleanup tmp dir
        if (!FileSystemUtils.deleteRecursively(workingDir.toFile())) {
            LOG.warn("Could not delete temporary directory {}", workingDir);
        }
    }

    /**
     * Write files to the file system
     *
     * @param workingDir
     * @param files
     * @throws IOException
     */
    @SuppressWarnings("unused")
    private void writeFiles(Path workingDir, Map<String, byte[]> files) throws IOException {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            File file = new File(workingDir.toString(), entry.getKey());
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                throw new IOException("Cannot create directory " + file.getParentFile());
            }
            Files.write(file.toPath(), entry.getValue());
        }
    }

    private void commitAndPush(Git git, String authorName, String authorEmail, String message, UsernamePasswordCredentialsProvider credentials)
        throws GitAPIException {

        // git add .
        git.add().addFilepattern(".").call();
        if (LOG.isDebugEnabled()) {
            LOG.debug("git add all file");
        }

        // git commit
        RevCommit commit = git.commit().setAuthor(authorName, authorEmail).setMessage(message).call();
        LOG.info("git commit id {}", commit.getId());

        // git push -f, not merging but simply forcing the push (for now)
        Iterable<PushResult> pushResult = git.push().setCredentialsProvider(credentials).setForce(true).call();
        if (!pushResult.iterator().next().getMessages().equals("")) {
            LOG.warn("git push messages: {}", pushResult.iterator().next().getMessages());
        }
    }
}
