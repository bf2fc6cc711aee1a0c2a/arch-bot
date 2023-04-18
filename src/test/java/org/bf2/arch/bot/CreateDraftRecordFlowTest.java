package org.bf2.arch.bot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bf2.arch.bot.model.record.RecordPage;
import org.bf2.arch.bot.model.record.RecordId;
import org.bf2.arch.bot.model.record.RecordType;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTree;
import org.kohsuke.github.GHTreeEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
class CreateDraftRecordFlowTest {

    public static final String EXAMPLE_TEMPLATE = "---\n" +
            "num: 0 # allocate an id when the draft is created\n" +
            "title: ADR template\n" +
            "status: \"Draft\" # One of Draft, Accepted, Rejected\n" +
            "authors:\n" +
            "  - \"\" # One item for each author, as github id or \"firstname lastname\"\n" +
            "tags:\n" +
            "  - \"\" # e.g. kafka, connectors, registry\n" +
            "applies_padrs: # What PADRs does this ADR apply?\n" +
            "applies_patterns: # What APs does this ADR apply?\n" +
            "---\n" +
            "Hello, world\n";

    @Test
    public void renderTemplateTest() throws IOException {
        RecordPage example = RecordPage.fromContent(
                EXAMPLE_TEMPLATE);
        var rendered = CreateDraftRecordFlow.renderTemplate(new RecordId(RecordType.ADR, 12),
                "Foo",
                List.of("me"),
                List.of("bar"),
                example);
        assertEquals(
                "---\n" +
                "num: 12\n" +
                "title: \"Foo\"\n" +
                "status: \"Draft\"\n" +
                "authors:\n" +
                "- \"me\"\n" +
                "tags:\n" +
                "- \"bar\"\n" +
                "applies_padrs: null\n" +
                "applies_patterns: null\n" +
                "---\n" +
                "Hello, world\n", rendered.toContentString());
    }

    @Test
    public void renderSupersededTemplateTest() throws IOException {
        RecordPage example = RecordPage.fromContent(
                EXAMPLE_TEMPLATE);
        var rendered = CreateDraftRecordFlow.renderTemplate(new RecordId(RecordType.ADR, 12),
                "Foo",
                List.of("me"),
                List.of("bar"),
                example);
        assertEquals(
                "---\n" +
                        "num: 12\n" +
                        "title: \"Foo\"\n" +
                        "status: \"Draft\"\n" +
                        "authors:\n" +
                        "- \"me\"\n" +
                        "tags:\n" +
                        "- \"bar\"\n" +
                        "applies_padrs: null\n" +
                        "applies_patterns: null\n" +
                        "---\n" +
                        "Hello, world\n", rendered.toContentString());
    }

    @Test
    public void testAllocateId() throws IOException {
        String commitSha = "123a";

        var repo = mock(GHRepository.class);
        var commit = mock(GHCommit.class);
        var tree = mock(GHTree.class);
        var entry = mock(GHTreeEntry.class);
        var tree2 = mock(GHTree.class);
        var adr3 = mock(GHTreeEntry.class);
        var adr12 = mock(GHTreeEntry.class);

        when(repo.getCommit(commitSha)).thenReturn(commit);
        when(commit.getTree()).thenReturn(tree);
        when(tree.getEntry(RecordType.ADR.repoDir)).thenReturn(entry);
        when(entry.asTree()).thenReturn(tree2);
        when(tree2.getTree()).thenReturn(List.of(adr3, adr12));
        when(adr3.getPath()).thenReturn("3");
        when(adr12.getPath()).thenReturn("12");

        assertEquals(13, new CreateDraftRecordFlow().allocateId(repo, commitSha, RecordType.ADR));
    }

    @Test
    public void testGetPage() throws IOException {
        var repo = mock(GHRepository.class);
        var branch = mock(GHBranch.class);
        var content = mock(GHContent.class);

        when(repo.getFileContent("_adr/0/index.adoc", "123a")).thenReturn(content);
        when(content.read()).thenAnswer(i -> new ByteArrayInputStream(EXAMPLE_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        when(branch.getSHA1()).thenReturn("123a");

        var page = CreateDraftRecordFlow.getPage(repo, branch, "_adr/0/index.adoc");

        assertEquals(0, page.frontMatter.num);
        assertTrue(page.bodyContent.contains("Hello, world"));
    }

    // TODO test supersededContent

}
