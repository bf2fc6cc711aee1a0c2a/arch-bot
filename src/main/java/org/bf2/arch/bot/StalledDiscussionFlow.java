/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bf2.arch.bot;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueSearchBuilder;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkiverse.githubapp.runtime.github.GitHubService;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class StalledDiscussionFlow {

    private static final Logger LOG = LoggerFactory.getLogger(StalledDiscussionFlow.class);
    public static final String ENABLE = "bot.enable.stalled-discussion";

    @ConfigProperty(name = "bot.installation.id")
    Long installationId;

    @ConfigProperty(name = ENABLE, defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "repository.path")
    String repositoryPath;

    private Date lastRan = new Date(0);

    GitHub client;
    ArchBotConfig config;

    @Inject
    GitHubService service;

    @Inject
    void init(GitHubConfigFileProvider configFileProvider) throws IOException {
        if (!enabled) {
            LOG.debug("Ignoring init: disabled due to {}=false", ENABLE);
        } else if (installationId != null) {
            // TODO parameterise this installationId
            client = service.getInstallationClient(installationId);

            Optional<ArchBotConfig> oconfig = configFileProvider.fetchConfigFile(client.getRepository(repositoryPath),
                                                                                 Util.CONFIG_REPO_PATH, ConfigFile.Source.DEFAULT, ArchBotConfig.class);
            config = oconfig.orElseThrow();
        } else {
            throw new RuntimeException("installation id is requied");
        }
    }

    /**
     * When
     * every N hours
     * query
     * https://github.com/pulls?q=
     *   is%3Aopen+
     *   repo%3A%22<org/repo>%22+
     *   is%3Apr+
     *   label%3A%22state%3A+needs-reviewers%22%2C%22state%3A+being-reviewed%22+
     *   -label%3A%22notice%3A+overdue%22+
     *   sort%3Aupdated-asc
     * for each PR:
     * If last review comment, or last PR comment was > X hours ago then add the
     * "stalled-discussion" label
     * MAS Arch meeting triages the corresponding query
     * If last review comment, or last PR comment was > X hours ago then remove the
     * "stalled-discussion" label
     * If the PR has been opened for > Y hours then "stalled-discussion"
     */
    // TODO similar method as this, but for OVERDUE
    @Scheduled(every = "{BOT_STALLED_DISCUSSION_FLOW_POLL_DURATION}")
    public void checkForStalledDiscussions() throws IOException {
        if (!enabled) {
            LOG.debug("Ignoring scheduled trigger: disabled due to {}=false", ENABLE);
            return;
        }
        long now = System.currentTimeMillis();
        long thresh = now - 24*40*60*1000L;
        LOG.info("Checking for stalled discussions");

        LOG.debug("Updating installation client to get new token (expires every 1h)");
        client = service.getInstallationClient(installationId);

        var results = client.searchIssues()
                .isOpen()
                .q("repo:" + repositoryPath)
                .q("is:pr")
                // multiple labels in a label query term => OR, see https://github.com/github/feedback/discussions/4507
                // whereas multiple label query terms => AND
                .q("label:\"" + Labels.STATE_NEEDS_REVIEWERS + "\",\"" + Labels.STATE_BEING_REVIEWED + "\"")
                .q("-label:\"" + Labels.NOTICE_OVERDUE + "\"")
                .sort(GHIssueSearchBuilder.Sort.UPDATED)
                .order(GHDirection.ASC)
                .list();
        LOG.info("Top-level query found {} PRs", results.getTotalCount());
        for (GHIssue issue : results) {
            try {
                GHPullRequest pullRequest = Util.findPullRequest(issue);
                if (pullRequest == null) {
                    LOG.info("Issue#{} is not a PR, ignoring", issue.getNumber());
                    continue;
                }
                // TODO calling listReviewComments() like this is inefficient
                // we're really interested in them in created sort
                // and since the last time we ran
                // those are supported by the github API
                // https://docs.github.com/en/rest/pulls/comments#list-review-comments-in-a-repository
                // but the client doesn't expose them
                Date lastCommentDate;
                LOG.info("COMMENTS COUNT: {}", pullRequest.listReviewComments().toList().size());
                var mostRecent = pullRequest.listReviewComments().toList().stream()
                    .filter(pr -> {
                            try {
                                return !Util.isThisBot(config, pr.getUser());
                            } catch (IOException e) {
                                return true;
                            }
                        })
                    .map(comment -> {
                            try {
                                LOG.info("Comment: {}", comment.getBody());
                                LOG.info("User: {}", comment.getUser().getLogin());
                                return comment.getCreatedAt();
                            } catch (IOException e) {
                                return new Date(0);
                            }
                        }).max(Date::compareTo);

                lastCommentDate = mostRecent.orElseGet(() -> {
                        try {
                            return pullRequest.getUpdatedAt();
                        } catch (IOException e) {
                            try {
                                return pullRequest.getCreatedAt();
                            } catch (IOException ex) {
                                return new Date(0);
                            }
                        }
                    });
                LOG.info("PR#{}: Last comment time {}", pullRequest.getNumber(), lastCommentDate);

                if (lastCommentDate.getTime() < thresh) {
                    LOG.info("PR#{}: adding {} label", pullRequest.getNumber(), Labels.NOTICE_STALLED_DISCUSSION);
                    Set<String> labels = Util.existingLabels(pullRequest);
                    labels.add(Labels.NOTICE_STALLED_DISCUSSION);
                    Util.setLabels(pullRequest, labels);
                }
            } catch (URISyntaxException | IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
