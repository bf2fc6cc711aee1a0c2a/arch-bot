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
import java.util.Set;
import java.util.stream.Collectors;

import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.common.constraint.NotNull;

/**
 * Common helper functions
 */
public class Util {

    private static final Logger LOG = LoggerFactory.getLogger(Util.class);

    public static final String CONFIG_REPO_PATH = "bf2-arch-bot.yml";

    private Util() { }

    static boolean isThisBot(ArchBotConfig config, GHUser user) throws IOException {
        String userLogin = user.getLogin();
        LOG.info("Me {} PR sender {}", config.botUserLogin, userLogin);
        return config.botUserLogin.equals(userLogin);
    }

    /**
     * Returns the {@code GHPullRequest} for a PR issue, or null if the issue is not a PR issue.
     * @param issue The issue (which may or may not be a PR issue)
     * @return The pull request, or null.
     */
    static GHPullRequest findPullRequest(GHIssue issue) throws URISyntaxException, IOException {
        if (issue.isPullRequest()) {
            GHRepository repository = issue.getRepository();
            int num = prNumber(issue.getPullRequest());
            GHPullRequest pullRequest = repository.getPullRequest(num);
            return pullRequest;
        } else {
            return null;
        }
    }

    /**
     * Gets the PR number from the issue shadowing a pull request.
     */
    private static int prNumber(GHIssue.PullRequest pullRequest) throws URISyntaxException {
        int num = -1;
        String[] split = pullRequest.getUrl().toURI().getPath().split("/");
        for (int ii = split.length - 1; ii >= 0; ii--) {
            try {
                num = Integer.parseInt(split[ii]);
                break;
            } catch (NumberFormatException e) {
                // carry on
            }
        }
        return num;
    }

    @NotNull
    static Set<String> existingLabels(GHPullRequest pullRequest) {
        return pullRequest.getLabels().stream().map(GHLabel::getName).collect(Collectors.toSet());
    }
    static void setLabels(GHPullRequest pullRequest, Set<String> labels) throws IOException {
        pullRequest.setLabels(labels.toArray(new String[0]));
    }
}
