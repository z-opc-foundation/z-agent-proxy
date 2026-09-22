package com.zifang.z.agent.proxy.adapter.aider;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class AiderAdapterTest {

    @Test
    public void metadata_methods_return_correct_values() {
        File repo = new File(System.getProperty("java.io.tmpdir"));
        AiderAdapter a = new AiderAdapter("test-aider", "/usr/local/bin/aider", repo);
        assertEquals("aider", a.productName());
        assertEquals("test-aider", a.adapterName());
        assertTrue(a.capabilities().contains("code-edit"));
        assertTrue(a.capabilities().contains("git-commit"));
        assertTrue(a.isHealthy());
        assertNotNull(a.endpoint());
    }

    @Test(expected = IllegalArgumentException.class)
    public void null_repo_throws() {
        new AiderAdapter("test", "aider", (String) null);
    }

    @Test
    public void close_marks_unhealthy() {
        File repo = new File(System.getProperty("java.io.tmpdir"));
        AiderAdapter a = new AiderAdapter("test", "aider", repo);
        assertTrue(a.isHealthy());
        a.close();
        assertFalse(a.isHealthy());
    }
}