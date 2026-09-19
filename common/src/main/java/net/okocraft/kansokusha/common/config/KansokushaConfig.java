package net.okocraft.kansokusha.common.config;

import org.jetbrains.annotations.NotNullByDefault;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@ConfigSerializable
@NotNullByDefault
public class KansokushaConfig {

    private static final String FILENAME = "config.yml";

    @Comment("More output to the console.")
    private boolean debug = false;

    public boolean debug() {
        return this.debug;
    }

    public static final class Holder {

        private final ConfigLoader<KansokushaConfig> loader;
        private final AtomicReference<KansokushaConfig> ref;

        public Holder(Path dataDirectory) {
            this.loader = new ConfigLoader<>(
                Objects.requireNonNull(dataDirectory).resolve(FILENAME),
                KansokushaConfig.class,
                KansokushaConfig::new
            );
            this.ref = new AtomicReference<>(new KansokushaConfig());
        }

        public KansokushaConfig get() {
            return this.ref.get();
        }

        public void reload() throws IOException {
            this.ref.set(this.loader.load());
        }
    }
}
