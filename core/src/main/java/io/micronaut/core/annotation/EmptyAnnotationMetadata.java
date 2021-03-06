/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.core.annotation;

import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.value.OptionalValues;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An empty representation of {@link AnnotationMetadata}.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
class EmptyAnnotationMetadata implements AnnotationMetadata {
    @Override
    public boolean hasDeclaredAnnotation(String annotation) {
        return false;
    }

    @Override
    public boolean hasAnnotation(String annotation) {
        return false;
    }

    @Override
    public boolean hasStereotype(String annotation) {
        return false;
    }

    @Override
    public boolean hasDeclaredStereotype(String annotation) {
        return false;
    }

    @Override
    public List<String> getAnnotationNamesByStereotype(String stereotype) {
        return Collections.emptyList();
    }

    @Override
    public Set<String> getAnnotationNames() {
        return Collections.emptySet();
    }

    @Override
    public Set<String> getDeclaredAnnotationNames() {
        return Collections.emptySet();
    }

    @Override
    public List<String> getDeclaredAnnotationNamesTypeByStereotype(String stereotype) {
        return Collections.emptyList();
    }

    @Override
    public ConvertibleValues<Object> getValues(String annotation) {
        return ConvertibleValues.empty();
    }

    @Override
    public ConvertibleValues<Object> getDeclaredValues(String annotation) {
        return ConvertibleValues.empty();
    }

    @Override
    public <T> OptionalValues<T> getValues(String annotation, Class<T> valueType) {
        return OptionalValues.empty();
    }

    @Override
    public <T> Optional<T> getDefaultValue(String annotation, String member, Class<T> requiredType) {
        return Optional.empty();
    }

    @Override
    public List<ConvertibleValues<Object>> getAnnotationValuesByType(Class<? extends Annotation> annotationType) {
        return Collections.emptyList();
    }

    @Override
    public List<ConvertibleValues<Object>> getDeclaredAnnotationValuesByType(Class<? extends Annotation> annotationType) {
        return Collections.emptyList();
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return null;
    }

    @Override
    public Annotation[] getAnnotations() {
        return AnnotationUtil.ZERO_ANNOTATIONS;
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return AnnotationUtil.ZERO_ANNOTATIONS;
    }
}
