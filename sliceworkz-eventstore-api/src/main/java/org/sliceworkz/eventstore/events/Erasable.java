package org.sliceworkz.eventstore.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.fasterxml.jackson.annotation.JsonView;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@JsonView(Erasable.class) // we just reuse the Erasable class
public @interface Erasable {
    String purpose() default "";
    Category category() default Category.PERSONAL;
    
    enum Category {
        PERSONAL,      // Name, address, etc.
        CONTACT,       // Email, phone
        FINANCIAL,     // Payment info
        HEALTH,        // Medical data
        BIOMETRIC      // Fingerprints, facial recognition
    }
}