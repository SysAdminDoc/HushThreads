/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at 44fb9338 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.hushthreads.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Report a problem fills GitHub's bug form by field id, and GitHub drops a value whose id the form
 * doesn't have without a word. Each id the link uses is held to the form in the repository.
 */
public class ReportProblemFormTest {
    @Test
    public void theLinkPointsAtThisRepositorysBugFormAndItsFields() throws IOException {
        assertEquals("SysAdminDoc/HushThreads", ReportProblemForm.REPOSITORY);
        assertEquals("github.com/SysAdminDoc/HushThreads/issues", ReportProblemForm.ISSUES_ADDRESS);
        File form = new File(root(), ".github/ISSUE_TEMPLATE/bug_report.yml");
        assertTrue("no bug form at " + form, form.isFile());
        String yaml = new String(Files.readAllBytes(form.toPath()), StandardCharsets.UTF_8);
        for (String id : Arrays.asList("versions", "device", "bug_description", "additional_context")) {
            assertTrue("bug_report.yml has no field " + id,
                    Pattern.compile("(?m)^\\s*id:\\s*" + Pattern.quote(id) + "\\s*$").matcher(yaml).find());
        }
    }

    /** The repository root, found from wherever Gradle runs the test. */
    private static File root() {
        for (File dir = new File("").getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
            if (new File(dir, "settings.gradle.kts").isFile() && new File(dir, "gradlew").isFile()) return dir;
        }
        throw new AssertionError("no repository root above " + new File("").getAbsolutePath());
    }
}
