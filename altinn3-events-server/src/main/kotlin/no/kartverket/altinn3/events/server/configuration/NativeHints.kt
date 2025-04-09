package no.kartverket.altinn3.events.server.configuration

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

class NativeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.resources()
            .registerPattern("application.yaml")
            .registerPattern("logback-spring.xml")

        // @RegisterReflectionForBinding(UserDto::class, User:class) can be used instead when using kotlin-reflection
//        hints.reflection().registerType<UserDto>(MemberCategory.INVOKE_DECLARED_METHODS)
//        hints.reflection().registerType<User>(MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS)
//        hints.reflection().registerType<User.Companion>(MemberCategory.INVOKE_DECLARED_METHODS)
    }
}
