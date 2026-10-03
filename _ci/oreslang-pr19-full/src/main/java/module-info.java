module dev.oreslang {
    requires java.base;
    requires java.logging;
    requires java.management;
    requires jdk.management;
    requires org.graalvm.polyglot;
    requires org.graalvm.truffle;

    exports dev.oreslang.launcher;
    exports dev.oreslang.runtime;

    provides com.oracle.truffle.api.provider.TruffleLanguageProvider
        with dev.oreslang.OresLanguageProvider;
}
