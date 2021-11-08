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
package org.commonjava.maven.ext.core.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.repository.DefaultMirrorSelector;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.core.fixture.StubTransport;
import org.commonjava.maven.ext.core.fixture.TestUtils;
import org.commonjava.maven.ext.core.state.VersioningState;
import org.commonjava.maven.ext.io.resolver.GalleyAPIWrapper;
import org.commonjava.maven.ext.io.resolver.GalleyInfrastructure;
import org.commonjava.maven.ext.io.resolver.MavenLocationExpander;
import org.commonjava.maven.ext.io.rest.handler.AddSuffixJettyHandler;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.spi.transport.Transport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class VersioningCalculatorTest
{
    private static final Logger logger = LoggerFactory.getLogger( VersioningCalculatorTest.class );

    private static final String GROUP_ID = "group.id";

    private static final String ARTIFACT_ID = "artifact-id";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private TestVersionCalculator modder;

    private ManipulationSession session;

    @Test
    public void testDefault()
            throws Exception
    {
        final Properties props = new Properties();

        setupSession( props );

        final String v = "1";
        final String result = calculate( v );

        assertThat( result, equalTo( v ) );

    }

    @Test
    public void testFill()
            throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.VERSION_FILL_SYSPROP, "true" );
        setupSession( props );

        final String v = "1";
        final String result = calculate( v );

        assertThat( result, equalTo( v + ".0.0" ) );

        final String v2 = "1.0";
        final String result2 = calculate( v2 );

        assertThat( result, equalTo( v2 + ".0" ) );
    }

    @Test
    public void initFailsWithoutSuffixProperty()
        throws Exception
    {
        final VersioningState state = setupSession( new Properties() );
        assertThat( state.isEnabled(), equalTo( false ) );
    }

    @Test
    public void applyNonSerialSuffix_NonNumericVersionTail_WithProperty()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "${property}";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void osgi_fixups()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        String[][] data = {
            {"1", "1.0.0"},
            {"6.2.0-SNAPSHOT", "6.2.0"},
            {"1.21", "1.21.0"},
            {"1.21.0", "1.21.0"},
            {"1.21-GA", "1.21.0.GA"},
            {"1.21-GA-GA", "1.21.0.GA-GA"},
            {"1.21.0.GA", "1.21.0.GA"},
            {"1.21.GA", "1.21.0.GA"},
            {"1.21.GA_FINAL", "1.21.0.GA_FINAL"},
            {"1.21.GA_ALPHA123", "1.21.0.GA_ALPHA123"},
            {"1.21.0-GA", "1.21.0.GA"}
            };

        for ( String[] v : data )
        {
            final String result = calculate( v[0] );

            // If expected result contains a qualifier append a '-' instead of '.'
            if ( v[1].contains( "GA" ) )
            {
                assertThat( result, equalTo( v[1] + "-" + s ) );
            }
            else
            {
                assertThat( result, equalTo( v[1] + "." + s ) );
            }
        }
    }

    /**
     * Not every version form know to man is converted...but we should try to
     * safely handle unknown forms.
     */
    @Test
    public void osgi_fallback()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        String[][] data = {
            {"GA-1-GA", "GA-1-GA"},
            {"1.0.0.0.0-GA", "1.0.0.0-0-GA"}
        };

        for ( String[] v : data )
        {
            final String result = calculate( v[0] );

            if ( v[1].contains( "GA" ) )
            {
                assertThat( result, equalTo( v[1] + "-" + s ) );
            }
            else
            {
                assertThat( result, equalTo( v[1] + "." + s ) );
            }
        }
    }

    @Test
    public void applyNonSerialSuffix_NonNumericVersionTail_WithOSGiDisabled()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_OSGI_SYSPROP, "false" );
        props.setProperty( VersioningState.VERSION_FILL_SYSPROP, "false" );
        setupSession( props );

        final String v = "1.2.GA";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applyNonSerialSuffix_NonNumericVersionTail_WithOSGiDisabledFill()
            throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_OSGI_SYSPROP, "false" );
        props.setProperty( VersioningState.VERSION_FILL_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.2.GA";

        final String result = calculate( v );
        assertThat( result, equalTo( "1.2.0.GA" + "-" + s ) );
    }

    @Test
    public void idempotency()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );

        result = calculate( result );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applyNonSerialSuffix_NumericVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applyNonSerialSuffix_NumericVersionTail_CompoundQualifier()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-bar";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applyNonSerialSuffix_NonNumericVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySerialSuffix_SPnVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.SP4";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySerialSuffix_NumericVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applySerialSuffix_NumericVersionTail_Snapshot()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "ER2-rht-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "6.2.0-SNAPSHOT";
        final String vr = "6.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( vr + "." + s ) );
    }

    @Test
    public void applySerialSuffix_NumericVersionTail_CompoundQualifier()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-bar-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applySerialSuffix_NonNumericSuffixInVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA-foo";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-1" ) );
    }

    @Test
    public void applySerialSuffix_Timestamp()
                    throws Exception
    {
        final Properties props = new Properties();

        final String s = "t-20170216-223844-555-foo";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.t-20170216-223844-555-foo";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-1" ) );
    }

    @Test
    public void applySerialSuffix_SimpleSuffixProperty()
                    throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String originalVersion = "1.0.0.Final";
        final String calcdVersion = "1.0.0.Final-foo-1";

        final String result = calculate( originalVersion );
        assertThat( result, equalTo( calcdVersion ) );
    }

    @Test
    public void applySerialSuffixWithPadding_SimpleSuffixProperty()
                    throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_PADDING_SYSPROP, "3" );
        setupSession( props );

        final String originalVersion = "1.0.0.Final";
        final String calcdVersion = "1.0.0.Final-foo-001";

        final String result = calculate( originalVersion );
        assertThat( result, equalTo( calcdVersion ) );
    }

    @Test
    public void applySerialSuffix_NonNumericNonSuffixInVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA-jdcasey";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySerialSuffix_NonNumericVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySerialSuffix_NumericVersionTail_OverwriteExisting()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";
        final String os = ".foo-1";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applySerialSuffix_NonNumericVersionTail_OverwriteExisting()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA";
        final String os = "-foo-1";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySuffixBeforeSNAPSHOT()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_SUFFIX_SNAPSHOT_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.2.0.GA";
        final String sn = "-SNAPSHOT";

        final String result = calculate( v + sn );
        assertThat( result, equalTo( v + "-" + s + sn ) );
    }

    @Test
    public void applySuffixBeforeSNAPSHOT_OSGI()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_SUFFIX_SNAPSHOT_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.2";
        final String sn = "-SNAPSHOT";

        final String result = calculate( v + sn );

        assertThat( result, equalTo( v + ".0." + s + sn ) );
    }

    @Test
    public void applySuffixBeforeSNAPSHOT_OSGI_2()
                    throws Exception
    {
        final Properties props = new Properties();

        final String s = "rebuild-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_SUFFIX_SNAPSHOT_SYSPROP, "true" );
        setupSession( props );

        final String v = "4.8-2";
        final String sn = "-SNAPSHOT";

        final String result = calculate( v + sn );

        assertThat( result, equalTo( "4.8.2." + s + sn ) );
    }

    @Test
    public void applySuffixBeforeSNAPSHOT_OverwriteExisting()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_SUFFIX_SNAPSHOT_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.2.0.GA";
        final String sn = "-SNAPSHOT";
        final String os = "-foo-1";

        final String result = calculate( v + os + sn );
        assertThat( result, equalTo( v + "-" + s + sn ) );
    }

    @Test
    public void applySuffixReplaceSNAPSHOT()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String originalVersion = "1.0.0.Final-foo-SNAPSHOT";
        final String calcdVersion = "1.0.0.Final-foo-1";

        final String result = calculate( originalVersion );
        assertThat( result, equalTo( calcdVersion ) );
    }

    @Test
    public void applyManualSuffixReplaceSNAPSHOT()
                    throws Exception
    {
        final Properties props = new Properties();

        final String s = "201212-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String originalVersion = "1.0.0.Final-SNAPSHOT";
        final String calcdVersion = "1.0.0.Final-201212-1";

        final String result = calculate( originalVersion );
        assertThat( result, equalTo( calcdVersion ) );
    }

    @Test
    public void applyManualSuffixReplaceSNAPSHOT2()
                    throws Exception
    {
        final Properties props = new Properties();

        final String s = "jbossorg-201212";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String originalVersion = "1.0.0.Final-SNAPSHOT";
        final String calcdVersion = "1.0.0.Final-jbossorg-201212";

        final String result = calculate( originalVersion );
        assertThat( result, equalTo( calcdVersion ) );
    }

    @Test
    public void applySerialSuffix_InvalidOSGi()
        throws Exception
    {
        final Properties props = new Properties();

        final String origVersion = "1.2.3.4.Final";
        final String suffix = "foo-1";
        final String newVersion = "1.2.3.4-Final-foo-1";

        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, suffix );
        setupSession( props );

        final String result = calculate( origVersion );
        assertThat( result, equalTo( newVersion ) );
    }

    @Test
    public void incrementExistingSerialSuffix()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props );

        final String v = "1.2.0.GA";
        final String os = "-foo-1";
        final String ns = "-foo-1";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix2()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props, "1.2.0.foo-2" );

        final String v = "1.2.0";
        final String os = ".foo-1";
        final String ns = ".foo-3";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix3()
                    throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-foo-1", "1.2.0-foo-2" );

        final String v = "1.2.0.foo-3";

        final String result = calculate( v );
        assertThat( result, equalTo( v ) );
    }

    @Test
    public void incrementExistingSerialSuffix4()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-foo-1", "1.2.0-foo-2" );

        final String v = "1.2.0.foo-4";

        final String result = calculate( v );
        assertThat( result, equalTo( v ) );
    }

    @Test
    public void incrementExistingSerialSuffix5()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-foo-1", "1.2.0-foo-4" );

        final String origVersion = "1.2.0.foo-2";
        final String newVersion = "1.2.0.foo-5";

        final String result = calculate( origVersion );
        assertThat( result, equalTo( newVersion ) );
    }

    @Test
    public void incrementExistingSerialSuffix6()
                    throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-foo-8", "1.2.0-foo-9" );

        final String v = "1.2.0.foo-10";

        final String result = calculate( v );
        assertThat( result, equalTo( v ) );
    }

    @Test
    public void incrementExistingSerialSuffixTimestamp1()
                    throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "t-20170216-223844-555-foo" );
        setupSession( props, "1.2.0-t-20170216-223844-555-foo-8", "1.2.0-t-20170216-223844-555-foo-9" );

        final String newVersion = "1.2.0.t-20170216-223844-555-foo-10";
        final String v = "1.2.0.t-20170216-223844-555-foo-5";

        final String result = calculate( v );
        assertThat( result, equalTo( newVersion ) );
    }

    @Test
    public void incrementExistingSerialSuffix7()
            throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-bar" );
        setupSession( props );

        final String v = "7.0.0.beta-foo-bar-1";
        final String newVersion = "7.0.0.beta-foo-bar-1";

        final String result = calculate( v );
        assertThat( result, equalTo( newVersion ) );
    }

    @Test
    public void incrementExistingSerialSuffix8()
                    throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-foo-1", "1.2.0-foo-4" );

        final String origVersion = "1.2.0.foo_2";
        final String newVersion = "1.2.0.foo_5";

        final String result = calculate( origVersion );
        assertThat( result, equalTo( newVersion ) );
    }

    @Test
    public void incrementExistingSerialSuffixWithPadding()
                    throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_PADDING_SYSPROP, "3" );
        setupSession( props, "1.2.0-foo-8", "1.2.0-foo-9" );

        final String v = "1.2.0.foo-010";

        final String result = calculate( v );
        assertThat( result, equalTo( v ) );
    }

    @Test
    public void incrementExistingSuffixWithPadding()
                    throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, "foo-10" );
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_PADDING_SYSPROP, "3" );
        setupSession( props, "1.2.0" );

        final String v = "1.2.0.foo-10";

        final String result = calculate( v );
        assertThat( result, equalTo( v ) );
    }

    @Test
    public void incrementExistingSerialSuffix_CompoundQualifier()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-bar-0" );
        setupSession( props, "1.2.0.GA-foo-bar-1", "1.2.0.GA-foo-bar-2" );

        final String v = "1.2.0.GA";
        final String os = "-foo-bar-1";
        final String ns = "-foo-bar-3";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_InvalidOSGi()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-GA-foo-1" );

        final String origVer = "1.2.0-GA-foo-1";
        final String updatedVer = "1.2.0.GA-foo-2";

        final String result = calculate( origVer );
        assertThat( result, equalTo( updatedVer ) );
    }

    @Test
    public void incrementExistingSerialSuffix_InvalidOSGi_SNAPSHOT()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props );

        final String origVer = "4.3.3-foo-SNAPSHOT";
        final String updatedVer = "4.3.3.foo-1";

        final String result = calculate( origVer );
        assertThat( result, equalTo( updatedVer ) );
    }

    @Test
    public void incrementExistingSerialSuffix_UsingRepositoryMetadata()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props, "1.2.0.GA-foo-3", "1.2.0.GA-foo-2", "1.2.0.GA-foo-9" );

        final String v = "1.2.0.GA";
        final String os = "-foo-1";
        final String ns = "-foo-10";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_withOsgiVersionChangeExistingVersionsMicro()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2-foo-1", "1.2.foo-2" );

        final String v = "1.2";
        final String ns = "foo-3";

        final String result = calculate( v );
        assertThat( result, equalTo( v + ".0." + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_TwoProjects_UsingRepositoryMetadata_AvailableOnlyForOne()
        throws Exception
    {
        final String v = "1.2.0.GA";
        final String os = "-foo-1";
        final String ns = "foo-10";

        final Model m1 = new Model();
        m1.setGroupId( GROUP_ID );
        m1.setArtifactId( ARTIFACT_ID );
        m1.setVersion( v + os );
        final Project p1 = new Project( m1 );

        final String a2 = ARTIFACT_ID + "-dep";
        final Model m2 = new Model();
        m2.setGroupId( GROUP_ID );
        m2.setArtifactId( a2 );
        m2.setVersion( v + os );
        final Project p2 = new Project( m2 );

        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props, "1.2.0.GA-foo-3", "1.2.0.GA-foo-2", "1.2.0.GA-foo-9" );

        final Map<ProjectVersionRef, String>
                        result = modder.calculateVersioningChanges( Arrays.asList( p1, p2 ), session );

        assertThat( result.get( new SimpleProjectVersionRef( GROUP_ID, ARTIFACT_ID, v + os ) ),
                equalTo( v + "-" + ns ) );
        assertThat( result.get( new SimpleProjectVersionRef( GROUP_ID, a2, v + os ) ), equalTo( v + "-" + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_TwoProjects_UsingRepositoryMetadata_DifferentAvailableIncrements()
        throws Exception
    {
        final String v = "1.2.0.GA";
        final String os = "-foo-1";
        final String ns = "foo-10";

        final Model m1 = new Model();
        m1.setGroupId( GROUP_ID );
        m1.setArtifactId( ARTIFACT_ID );
        m1.setVersion( v + os );
        final Project p1 = new Project( m1 );

        final String a2 = ARTIFACT_ID + "-dep";
        final Model m2 = new Model();
        m2.setGroupId( GROUP_ID );
        m2.setArtifactId( a2 );
        m2.setVersion( v + os );
        final Project p2 = new Project( m2 );

        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        final Map<ProjectRef, String[]> versionMap = new HashMap<>( 2 );

        versionMap.put( new SimpleProjectRef( p1.getGroupId(), p1.getArtifactId() ), new String[] { "1.2.0.GA-foo-3",
            "1.2.0.GA-foo-2", "1.2.0.GA-foo-9" } );
        versionMap.put( new SimpleProjectRef( p2.getGroupId(), p2.getArtifactId() ), new String[] { "1.2.0.GA-foo-3",
            "1.2.0.GA-foo-2" } );

        setupSession( props, versionMap );

        final Map<ProjectVersionRef, String>
                        result = modder.calculateVersioningChanges( Arrays.asList( p1, p2 ), session );

        assertThat( result.get( new SimpleProjectVersionRef( GROUP_ID, ARTIFACT_ID, v + os ) ),
                equalTo( v + "-" + ns ) );
        assertThat( result.get( new SimpleProjectVersionRef( GROUP_ID, a2, v + os ) ), equalTo( v + "-" + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_UsingRepositoryMetadataWithIrrelevantVersions()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "redhat-0" );
        setupSession( props, "0.0.1", "0.0.2", "0.0.3", "0.0.4", "0.0.5", "0.0.6", "0.0.7", "0.0.7.redhat-1" );

        final String v = "0.0.7";
        final String ns = AddSuffixJettyHandler.EXTENDED_SUFFIX;

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_Property()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props );

        final String v = "${property}";
        final String os = "-foo-1";
        final String ns = "foo-1";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "-" + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_withEmptySuffixBase()
            throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "" );
        setupSession( props, "1.2.0.1", "1.2.0.2" );

        final String v = "1.2.0";
        final String ns = "3";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + ns ) );
    }

    @Test
    public void alphaNumericSuffixBase()
            throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, "test-jdk7" );
        props.setProperty( VersioningState.VERSION_OSGI_SYSPROP, "false" );
        props.setProperty( VersioningState.VERSION_FILL_SYSPROP, "false" );
        setupSession( props );

        final String v = "1.1";
        final String os = "";
        final String ns = ".test-jdk7";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + ns ) );
    }

    @Test
    public void alphaNumericSuffixBaseFill()
            throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, "test-jdk7" );
        props.setProperty( VersioningState.VERSION_OSGI_SYSPROP, "false" );
        props.setProperty( VersioningState.VERSION_FILL_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.1";
        final String os = "";
        final String ns = ".test-jdk7";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + ".0" + ns ) );
    }

    @Test
    public void alphaNumericSuffixBaseOSGi()
            throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, "Beta1" );
        props.setProperty( VersioningState.VERSION_OSGI_SYSPROP, "true" );
        setupSession( props );

        final String v = "1";
        final String os = "";
        final String ns = ".Beta1";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + ".0.0" + ns ) );
    }

    @Test
    public void alphaNumericSuffixBaseOSGiTimestamp()
                    throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, "t171222-2230-rebuild-1" );
        props.setProperty( VersioningState.VERSION_OSGI_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.1";
        final String ns = ".t171222-2230-rebuild-1";

        final String result = calculate( v );
        assertThat( result, equalTo( v + ".0" + ns ) );
    }

    @Test
    public void alphaNumericSuffixBaseWithSnapshot()
            throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, "test_jdk7" );
        props.setProperty( VersioningState.VERSION_OSGI_SYSPROP, "false" );
        props.setProperty( VersioningState.VERSION_FILL_SYSPROP, "false" );
        props.setProperty( VersioningState.VERSION_SUFFIX_SNAPSHOT_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.1-SNAPSHOT";
        final String os = "";
        final String ns = ".test_jdk7-SNAPSHOT";

        final String result = calculate( v + os );
        assertThat( result, equalTo( "1.1" + ns ) );
    }

    @Test
    public void alphaNumericSuffixBaseWithSnapshotFill()
            throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, "test_jdk7" );
        props.setProperty( VersioningState.VERSION_OSGI_SYSPROP, "false" );
        props.setProperty( VersioningState.VERSION_FILL_SYSPROP, "true" );
        props.setProperty( VersioningState.VERSION_SUFFIX_SNAPSHOT_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.1-SNAPSHOT";
        final String os = "";
        final String ns = ".test_jdk7-SNAPSHOT";

        final String result = calculate( v + os );
        assertThat( result, equalTo( "1.1.0" + ns ) );
    }

    @Test
    public void verifyPadding()
                    throws Exception
    {
        final Properties props = new Properties();
        setupSession( props );

        final int padding = Version.getBuildNumberPadding( 0, Collections.singleton( "1.2.0.GA-foo-0" ) );
        assertThat( padding, is( 1 ) );

        final int padding2 = Version.getBuildNumberPadding(  0, Collections.singleton( "1.2.0.GA-foo-01" ) );
        assertThat( padding2, is( 2 ) );

        final int padding3 = Version.getBuildNumberPadding( 0, Collections.singleton( "1.2.0.GA-foo-101" ) );
        assertThat( padding3, is( 3 ) );

        final int padding4 = Version.getBuildNumberPadding( 0, Collections.singleton( "1.2.0.GA-foo-001" ) );
        assertThat( padding4, is( 3 ) );

        final int padding5 = Version.getBuildNumberPadding( 0, Collections.singleton( "1.2.0.GA-foo-9" ) );
        assertThat( padding5, is( 1 ) );

        final int padding6 = Version.getBuildNumberPadding( 0, Collections.singleton( "1.0.0.redhat-1" ) );
        assertThat( padding6, is( 1 ) );

        final int padding7 = Version.getBuildNumberPadding( 0,
                Collections.singleton( "1.0.0.Final.rebuild-01912-01" ) );
        assertThat( padding7, is( 2 ) );

        final int padding8 = Version.getBuildNumberPadding( 0, Collections.singleton( "1.0.0.GA.rebuild-010" ) );
        assertThat( padding8, is( 3 ) );
    }

    @Test
    public void verifyPaddingSuffix()
    {
        final String paddedBuildNum = StringUtils.leftPad( "1", 0, '0' );
        logger.debug( "### got {}", paddedBuildNum );
        assertThat( paddedBuildNum, is( "1" ) );

        final String paddedBuildNum2 = StringUtils.leftPad("1", 1, '0');
        logger.debug( "### got {}", paddedBuildNum2 );
        assertThat( paddedBuildNum2, is( "1" ) );

        final String paddedBuildNum3 = StringUtils.leftPad("1", 2, '0');
        logger.debug( "### got {}", paddedBuildNum3 );
        assertThat( paddedBuildNum3, is( "01" ) );

        final String paddedBuildNum4 = StringUtils.leftPad("1", 3, '0');
        logger.debug( "### got {}", paddedBuildNum4 );
        assertThat( paddedBuildNum4, is( "001" ) );

        final String paddedBuildNum5 = StringUtils.leftPad("10", 3, '0');
        logger.debug( "### got {}", paddedBuildNum5 );
        assertThat( paddedBuildNum5, is( "010" ) );

        final String paddedBuildNum6 = StringUtils.leftPad("010", 3, '0');
        logger.debug( "### got {}", paddedBuildNum6 );
        assertThat( paddedBuildNum6, is( "010" ) );

        final String paddedBuildNum7 = StringUtils.leftPad("010", 6, '0');
        logger.debug( "### got {}", paddedBuildNum7 );
        assertThat( paddedBuildNum7, is( "000010" ) );
    }

    @Test
    public void incrementExistingSerialSuffix_TwoProjects_UsingRepositoryMetadata_AvailableOnlyForOne_Padding()
                    throws Exception
    {
        final String v = "1.2.0.GA";
        final String os = "-foo-001";
        final String ns = "foo-010";

        final Model m1 = new Model();
        m1.setGroupId( GROUP_ID );
        m1.setArtifactId( ARTIFACT_ID );
        m1.setVersion( v + os );
        final Project p1 = new Project( m1 );

        final String a2 = ARTIFACT_ID + "-dep";
        final Model m2 = new Model();
        m2.setGroupId( GROUP_ID );
        m2.setArtifactId( a2 );
        m2.setVersion( v + os );
        final Project p2 = new Project( m2 );

        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0.GA-foo-003", "1.2.0.GA-foo-002", "1.2.0.GA-foo-009" );

        logger.debug( "### Calculating versioning changes for projects {} and {}", p1, p2 );
        final Map<ProjectVersionRef, String>
                        result = modder.calculateVersioningChanges( Arrays.asList( p1, p2 ), session );

        assertThat( result.get( new SimpleProjectVersionRef( GROUP_ID, ARTIFACT_ID, v + os ) ),
                equalTo( v + "-" + ns ) );
        assertThat( result.get( new SimpleProjectVersionRef( GROUP_ID, a2, v + os ) ), equalTo( v + "-" + ns ) );
    }

    @Test
    public void checkVersionStripping ()
    {
        final Properties props = new Properties();
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "temporary-redhat" );
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_PADDING_SYSPROP, "5" );
        props.setProperty( VersioningState.VERSION_SUFFIX_ALT, "redhat" );

        final VersioningState vState = new VersioningState( props );

        assertThat( VersionCalculator.handleAlternate( vState, "1.0.0.temporary-redhat-1" ),
                is( "1.0.0.temporary-redhat-1" ) );
        assertThat( VersionCalculator.handleAlternate( vState, "1.0.0" ), is( "1.0.0" ) ) ;
        assertThat( VersionCalculator.handleAlternate( vState, "1.0.0.redhat-10" ) , is( "1.0.0" ) );

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "redhat" );
        props.remove( VersioningState.VERSION_SUFFIX_ALT );
        final VersioningState vState2 = new VersioningState( props );

        assertThat( VersionCalculator.handleAlternate ( vState2, "1.0.0.temporary-redhat-1" ),
                is( "1.0.0.temporary-redhat-1" ) );
        assertThat( VersionCalculator.handleAlternate( vState2, "1.0.0" ), is( "1.0.0"  ) ) ;
        assertThat( VersionCalculator.handleAlternate( vState2, "1.0.0.redhat-10" ), is( "1.0.0.redhat-10" ) );

    }

    @Test
    public void incrementExistingSuffixWithTemporary()
                    throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "temporary-redhat" );
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_PADDING_SYSPROP, "5" );
        props.setProperty( VersioningState.VERSION_SUFFIX_ALT, "redhat" );

        setupSession( props, "1.2.0.redhat-00002", "1.2.0.temporary-redhat-00001", "1.2.0.temporary-redhat-00002" );
        String oldValue = "1.2.0.redhat-00001";

        final String v = "1.2.0.temporary-redhat-00003";

        final String result = calculate( oldValue );
        assertThat( result, equalTo( v ) );
    }

    @Test
    public void incrementUsingCustomCalculatorAndRESTMetadata()
                    throws Exception
    {
        final Properties props = new Properties();
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "rebuild" );

        final VersioningState state = setupSession( props );
        final String origVersion = "1.2.0";
        final String candidateVersion = "1.2.0.rebuild-1";
        final String newVersion = "1.2.0.rebuild-2";

        final ProjectVersionRef projectVersionRef = SimpleProjectVersionRef.parse( "org.jboss:bar:" + origVersion );
        final Set<String> candidates = Collections.singleton( candidateVersion );
        final Map<ProjectRef, Set<String>> metadata = Collections.singletonMap( projectVersionRef.asProjectRef(),
                candidates );
        state.setRESTMetadata( metadata );

        final VersionCalculator vc = new VersionCalculator( null );
        final String result = vc.calculate( "org.jboss", "bar", origVersion, state );
        logger.debug( "State is {}", state );
        assertThat( result,  equalTo( newVersion ) );
    }

    @Test
    public void incrementUsingCustomCalculatorWithNoMetadata()
                    throws Exception
    {
        final Properties props = new Properties();
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "rebuild" );

        final VersioningState state = setupSession( props );
        final String origVersion = "1.2.0";
        final String newVersion = "1.2.0.rebuild-1";

        final VersionCalculator vc = new VersionCalculator( null );

        final String result = vc.calculate( "org.jboss", "bar", origVersion, state );
        logger.debug( "State is {}", state );
        assertThat( result,  equalTo( newVersion ) );
    }

    private static byte[] setupMetadataVersions( final String... versions ) {
        final Metadata md = new Metadata();
        final Versioning v = new Versioning();
        md.setVersioning( v );
        v.setVersions( Arrays.asList( versions ) );

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try
        {
            new MetadataXpp3Writer().write( baos, md );
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }

        return baos.toByteArray();
    }

    private String calculate( final String version )
        throws Exception
    {
        return modder.calculate( GROUP_ID, ARTIFACT_ID, version, session );
    }

    private VersioningState setupSession( final Properties properties, final String... versions )
        throws Exception
    {
        return setupSession( properties,
                Collections.singletonMap( new SimpleProjectRef( GROUP_ID, ARTIFACT_ID ), versions ) );
    }

    @SuppressWarnings( "deprecation" )
    private VersioningState setupSession( final Properties properties, final Map<ProjectRef, String[]> versionMap )
        throws Exception
    {
        // Originally the default used to be 0, this was changed to be 5 but this affects this test suite so revert
        // just for these tests.
        if ( !properties.containsKey( VersioningState.INCREMENT_SERIAL_SUFFIX_PADDING_SYSPROP ) )
        {
            properties.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_PADDING_SYSPROP, "0" );
        }

        final ArtifactRepository ar =
            new MavenArtifactRepository( "test", TestUtils.MVN_CENTRAL, new DefaultRepositoryLayout(),
                                         new ArtifactRepositoryPolicy(), new ArtifactRepositoryPolicy() );

        final MavenExecutionRequest req =
            new DefaultMavenExecutionRequest().setUserProperties( properties )
                                              .setRemoteRepositories( Collections.singletonList( ar ) );

        final PlexusContainer container = new DefaultPlexusContainer();

        final MavenSession mavenSession = new MavenSession( container, null, req, new DefaultMavenExecutionResult() );

        session = new ManipulationSession();
        session.setMavenSession( mavenSession );

        final VersioningState state = new VersioningState( properties );
        session.setState( state );

        final Map<String, byte[]> dataMap = versionMap.entrySet().stream()
                .collect( Collectors.toMap( entry -> toMetadataPath( entry.getKey() ),
                        entry -> setupMetadataVersions( entry.getValue() ) ) );
        final Location mdLoc = MavenLocationExpander.EXPANSION_TARGET;
        final Transport mdTrans = new StubTransport( dataMap );

        modder =
            new TestVersionCalculator( new ManipulationSession(), mdLoc, mdTrans, temp.newFolder( "galley-cache" ) );

        return state;
    }

    private static String toMetadataPath( final ProjectRef key )
    {
        return String.format( "%s/%s/maven-metadata.xml", key.getGroupId()
                                                             .replace( '.', '/' ), key.getArtifactId() );
    }

    public static final class TestVersionCalculator
        extends VersionCalculator
    {

        TestVersionCalculator( final ManipulationSession session, final Location mdLoc, final Transport mdTrans,
                               final File cacheDir )
            throws ManipulationException
        {
            super( new GalleyAPIWrapper( new GalleyInfrastructure( session, new DefaultMirrorSelector() ).init( mdLoc,
                                                                   mdTrans, cacheDir ) ) );
        }

        @Override
        public String calculate( final String groupId, final String artifactId,
                                             final String version, final ManipulationSession session )
            throws ManipulationException
        {
            return super.calculate( groupId, artifactId, version, session );
        }

    }
}
