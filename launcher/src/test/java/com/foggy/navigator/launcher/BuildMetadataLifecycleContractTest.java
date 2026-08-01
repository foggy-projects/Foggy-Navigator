package com.foggy.navigator.launcher;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildMetadataLifecycleContractTest {

    @Test
    void launcherLifecycleRegeneratesAndVerifiesProvenanceBeforePackaging() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        var document = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();

        assertEquals("initialize", xpath.evaluate(
                "/project/build/plugins/plugin[artifactId='git-commit-id-maven-plugin']"
                        + "/executions/execution[id='git-build-metadata']/phase", document));
        assertEquals("prepare-package", xpath.evaluate(
                "/project/build/plugins/plugin[artifactId='maven-antrun-plugin']"
                        + "/executions/execution[id='verify-launcher-provenance']/phase", document));
    }
}
