package io.github.core607.poketto.auth.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.util.regex.Pattern;
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
 * Java-serialized; reading admits only application, Spring Security and JDK classes. An
 * attribute that no longer reads, such as one written by an older release, or one that could not
 * be written counts as absent: the visitor signs in again instead of receiving an error.
 */
@Configuration(proxyBeanMethods = false)
class BrowserSessionConfiguration {
    private static final Logger log = LoggerFactory.getLogger(BrowserSessionConfiguration.class);
    private static final SerializingConverter WRITER = new SerializingConverter();
    private static final ObjectInputFilter ATTRIBUTE_CLASSES =
            ObjectInputFilter.Config.createFilter("maxdepth=32;maxrefs=10000;maxbytes=1048576;"
                    + "io.github.core607.poketto.**;org.springframework.security.**;java.**;!*");
    /** A class name and the JDK's reason it no longer reads: bounded, with no control characters. */
    private static final Pattern CLASS_MISMATCH = Pattern.compile("[\\w.$\\[;:=, -]{1,300}");

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
        conversion.addConverter(Object.class, byte[].class, BrowserSessionConfiguration::write);
        conversion.addConverter(byte[].class, Object.class, bytes -> read(bytes, loader));
        return conversion;
    }

    /**
     * A value that cannot be serialized is stored empty, which reads back as absent, so a new
     * session type that misses {@code Serializable} fails loudly in the log without failing requests.
     */
    private static byte[] write(Object value) {
        try {
            return WRITER.convert(value);
        } catch (RuntimeException unwritable) {
            log.error(
                    "A browser session attribute of type {} could not be stored and will read as absent",
                    value.getClass().getName(),
                    unwritable);
            return new byte[0];
        }
    }

    private static Object read(byte[] bytes, ClassLoader loader) {
        try (var input = new ConfigurableObjectInputStream(new ByteArrayInputStream(bytes), loader)) {
            input.setObjectInputFilter(ATTRIBUTE_CLASSES);
            return input.readObject();
        } catch (IOException | ClassNotFoundException | RuntimeException unreadable) {
            log.warn(
                    "A stored browser session attribute could not be read and is treated as absent: {}",
                    unreadableReason(unreadable));
            return null;
        }
    }

    /**
     * The failure without its stack trace or causes, which can carry attribute data. Only a class
     * mismatch keeps its message, which names the class and why it no longer reads, and only while
     * it has that shape: the class name comes from the stored bytes, so a damaged or altered record
     * cannot put other text in the log. Other messages can quote the stored bytes or the arguments a
     * constructor rejected, so they give only their type.
     */
    private static String unreadableReason(Exception unreadable) {
        String type = unreadable.getClass().getName();
        String message = unreadable.getMessage();
        boolean mismatch = unreadable instanceof InvalidClassException || unreadable instanceof ClassNotFoundException;
        return mismatch && message != null && CLASS_MISMATCH.matcher(message).matches() ? type + ": " + message : type;
    }
}
