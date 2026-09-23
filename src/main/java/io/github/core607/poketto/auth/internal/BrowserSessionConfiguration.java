package io.github.core607.poketto.auth.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;

/**
 * Browser sessions live in PostgreSQL so a restart or deploy keeps people signed in. Attributes are
 * Java-serialized; reading admits only application, Spring Security and JDK classes, and an
 * attribute that no longer reads, such as one written by an older release, counts as absent: the
 * visitor signs in again instead of receiving an error.
 */
@Configuration(proxyBeanMethods = false)
class BrowserSessionConfiguration {
    private static final Logger log = LoggerFactory.getLogger(BrowserSessionConfiguration.class);
    private static final ObjectInputFilter ATTRIBUTE_CLASSES =
            ObjectInputFilter.Config.createFilter("maxdepth=32;maxrefs=10000;maxbytes=1048576;"
                    + "io.github.core607.poketto.**;org.springframework.security.**;java.**;!*");

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> postgresSessionStatements() {
        return new PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
    }

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> tolerantSessionAttributes() {
        ConversionService conversion = attributeConversion(BrowserSessionConfiguration.class.getClassLoader());
        return repository -> repository.setConversionService(conversion);
    }

    static ConversionService attributeConversion(ClassLoader loader) {
        var conversion = new GenericConversionService();
        conversion.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversion.addConverter(byte[].class, Object.class, bytes -> read(bytes, loader));
        return conversion;
    }

    private static Object read(byte[] bytes, ClassLoader loader) {
        try (var input = new ConfigurableObjectInputStream(new ByteArrayInputStream(bytes), loader)) {
            input.setObjectInputFilter(ATTRIBUTE_CLASSES);
            return input.readObject();
        } catch (IOException | ClassNotFoundException | RuntimeException unreadable) {
            log.warn("A stored browser session attribute could not be read and is treated as absent");
            return null;
        }
    }
}
