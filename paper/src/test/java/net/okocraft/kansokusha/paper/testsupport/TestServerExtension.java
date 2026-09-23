package net.okocraft.kansokusha.paper.testsupport;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class TestServerExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        TestServer.setUp();
    }
}
