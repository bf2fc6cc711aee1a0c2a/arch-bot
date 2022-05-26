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

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.Set;

import io.quarkiverse.githubapp.runtime.github.GitHubService;
import io.quarkus.scheduler.Scheduled;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueSearchBuilder;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class StalledDiscussionFlow {

    private static final Logger LOG = LoggerFactory.getLogger(StalledDiscussionFlow.class);
    private Date lastRan = new Date(0);

    GitHub client;
    //ArchBotConfig config;

    @Inject
    void init(GitHubService service) {
        // TODO parameterise this installactionId
         client = service.getInstallationClient(Long.valueOf(System.getenv("INSTALLATION_ID")));
         // TODO load the config
    }



    /**
     * When
     * every N hours
     * query
     * https://github.com/tombentley/app-services-architecture/pulls?q=
     *   is%3Aopen+
     *   is%3Apr+
     *   label%3A%22state%3A+needs-reviewers%22+
     *   label%3A%22state%3A+being-reviewed%22+
     *   sort%3Aupdated-asc
     * for each PR:
     *   If last review comment, or last PR comment was > X hours ago then add the "stalled-discussion" label
     * MAS Arch meeting triages the corresponding query
     *   If last review comment, or last PR comment was > X hours ago then remove the "stalled-discussion" label
     *   If the PR has been opened for > Y hours then "stalled-discussion"
     */
    // TODO similar method as this, but for OVERDUE
    @Scheduled(every="60s")
    public void checkForStalledDiscussions() throws IOException {
        long now = System.currentTimeMillis();
        long thresh = now - 24*40*60*1000L;
        LOG.info("Checking for stalled discussions");
        // TODO need a parameter for this installation id

        var results = client.searchIssues()
                .isOpen()
                .q("is:pr")
                // multiple labels in a label query term => OR, see https://github.com/github/feedback/discussions/4507
                // whereas multiple label query terms => AND
                .q("label:\"" + Labels.STATE_NEEDS_REVIEWERS + "\",\"" + Labels.STATE_BEING_REVIEWED + "\"")
                .q("-label:\"" + Labels.NOTICE_OVERDUE + "\"")
                .sort(GHIssueSearchBuilder.Sort.UPDATED)
                .order(GHDirection.ASC)
                .list();
        LOG.info("Top-level query found {} PRs", results.getTotalCount());
        int processed = 0;
        for (GHIssue issue : results) {
//            if () {
//                lastRan = new Date();
//                break;
//            }
            processed++;
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
                var mostRecent = pullRequest.listReviewComments().toList().stream()
//                        .filter(pr -> {
//                            try {
//                                return !Util.isThisBot(config, pr.getUser());
//                            } catch (IOException e) {
//                                return true;
//                            }
//                        })
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
