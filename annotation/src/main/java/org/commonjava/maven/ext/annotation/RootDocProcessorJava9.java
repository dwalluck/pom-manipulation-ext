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

import jdk.javadoc.doclet.DocletEnvironment;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.type.TypeKind;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Process the {@link RootDoc} by substituting with (nested) proxy objects that
 * exclude elements with annotations.
 * <p>
 * https://gist.github.com/deeTEEcee/713e1c745f9e2eb9be2817aaa9294fb8/
 * https://github.com/apache/hadoop/blob/trunk/hadoop-common-project/hadoop-annotations/src/main/java/org/apache/hadoop/classification/tools/RootDocProcessor.java
 */
class RootDocProcessorJava9
{
    public static DocletEnvironment process( DocletEnvironment root )
    {
        return (DocletEnvironment) process( root, DocletEnvironment.class );
    }

    private static boolean exclude( Element doc )
    {
       List<? extends AnnotationMirror> annotations = null;
        //  class, interface, field, constructor, or method
        // method, constructor, or initializer
        if ( doc.asType().getKind().equals( TypeKind.DECLARED ) || doc.asType().getKind().equals( TypeKind.EXECUTABLE ) )
        {
            annotations = ( (Element) doc ).getAnnotationMirrors();
        }
        else if ( doc.asType().getKind().equals( TypeKind.PACKAGE ) )
        {
            annotations = ( (PackageElement) doc ).getAnnotationMirrors();
        }
        if ( annotations != null )
        {
            for ( AnnotationMirror annotation : annotations )
            {
                String qualifiedTypeName = annotation.getAnnotationType().toString();
                if ( qualifiedTypeName.equals( JavadocExclude.class.getCanonicalName() ) )
                {
                    return true;
                }
            }
        }
        // nothing above found a reason to exclude
        return false;
    }

    private static Object process( Object obj, Class<?> type )
    {
        if ( obj == null )
            return null;
        Class<?> cls = obj.getClass();
        if ( cls.getName().startsWith( "jdk.javadoc." ) )
        {
            return Proxy.newProxyInstance( cls.getClassLoader(), cls.getInterfaces(), new ExcludeHandler( obj ) );
        }
        else if ( obj instanceof Object[] )
        {
            Class<?> componentType = type.isArray() ? type.getComponentType() : cls.getComponentType();
            Object[] array = (Object[]) obj;
            List<Object> list = new ArrayList<>( array.length );
            for ( Object entry : array )
            {
                if ( ( entry instanceof Element ) && exclude( (Element) entry ) )
                {
                    continue;
                }
                list.add( process( entry, componentType ) );
            }
            return list.toArray( (Object[]) Array.newInstance( componentType, list.size() ) );
        }
        else
        {
            return obj;
        }
    }

    private static class ExcludeHandler
                    implements InvocationHandler
    {
        private final Object target;

        public ExcludeHandler( Object target )
        {
            this.target = target;
        }

        @Override
        public Object invoke( Object proxy, Method method, Object[] args ) throws Throwable
        {
            if ( args != null )
            {
                String methodName = method.getName();
                if ( methodName.equals( "compareTo" ) || methodName.equals( "equals" ) || methodName.equals(
                                "overrides" ) || methodName.equals( "subclassOf" ) )
                {
                    args[0] = unwrap( args[0] );
                }
            }
            try
            {
                return process( method.invoke( target, args ), method.getReturnType() );
            }
            catch ( InvocationTargetException e )
            {
                throw e.getTargetException();
            }
        }

        private Object unwrap( Object proxy )
        {
            if ( proxy instanceof Proxy )
            {
                return ( (ExcludeHandler) Proxy.getInvocationHandler( proxy ) ).target;
            }
            return proxy;
        }
    }
}
