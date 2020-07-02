/*
 * Copyright (C) 2012 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.commonjava.maven.ext.annotation;

import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

import javax.lang.model.SourceVersion;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * A <a href="https://docs.oracle.com/javase/9/docs/api/jdk/javadoc/doclet/package-summary.html">Doclet</a>
 * for excluding elements that are annotated with {@link JavadocExclude} to
 * decide what gets excluded from the Javadoc.
 * <br>
 * Derived from:
 * https://github.com/apache/hadoop/blob/trunk/hadoop-common-project/hadoop-annotations/src/main/java/org/apache/hadoop/classification/tools/ExcludePrivateAnnotationsStandardDoclet.java
 */
public class JavadocFilterJava9 implements Doclet
{
    @Override public void init( Locale locale, Reporter reporter )
    {

    }

    @Override public String getName()
    {
        return getClass().getSimpleName();
    }

    @Override public Set<? extends Option> getSupportedOptions()
    {
        return Collections.emptySet();
    }

    @Override public SourceVersion getSupportedSourceVersion()
    {
        return SourceVersion.RELEASE_5;
    }

    @Override public boolean run( DocletEnvironment environment )
    {
        RootDocProcessorJava9.process( environment );

        return true;
    }
}
