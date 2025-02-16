package org.apache.maven.surefire.api.util;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.surefire.api.runorder.RunEntryStatisticsMap;
import org.apache.maven.surefire.api.testset.ResolvedTest;
import org.apache.maven.surefire.api.testset.RunOrderParameters;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

import static org.apache.maven.surefire.api.testset.TestListResolver.toClassFileName;

/**
 * Applies the final runorder of the tests
 *
 * @author Kristian Rosenvold
 */
public class DefaultRunOrderCalculator
    implements RunOrderCalculator
{
    private final Comparator<Class<?>> sortOrder;

    private final RunOrder[] runOrder;

    private final RunOrderParameters runOrderParameters;

    private final int threadCount;

    private final Random random;

    private final List<ResolvedTest> specifiedRunOrder;

    public DefaultRunOrderCalculator( RunOrderParameters runOrderParameters, int threadCount )
    {
        this.runOrderParameters = runOrderParameters;
        this.threadCount = threadCount;
        this.runOrder = runOrderParameters.getRunOrder();
        this.specifiedRunOrder = runOrderParameters.resolvedSpecifiedRunOrder();
        this.sortOrder = this.runOrder.length > 0 ? getSortOrderComparator( this.runOrder[0] ) : null;
        Long runOrderRandomSeed = runOrderParameters.getRunOrderRandomSeed();
        random = new Random( runOrderRandomSeed == null ? System.nanoTime() : runOrderRandomSeed );
    }

    @Override
    @SuppressWarnings( "checkstyle:magicnumber" )
    public TestsToRun orderTestClasses( TestsToRun scannedClasses )
    {
        List<Class<?>> result = new ArrayList<>( 512 );

        for ( Class<?> scannedClass : scannedClasses )
        {
            result.add( scannedClass );
        }

        orderTestClasses( result, runOrder.length != 0 ? runOrder[0] : null );
        return new TestsToRun( new LinkedHashSet<>( result ) );
    }

    @Override
    public Comparator<String> comparatorForTestMethods()
    {
        if ( RunOrder.TESTORDER.equals( runOrder[0] ) && specifiedRunOrder != null )
        {
            return new Comparator<String>()
            {
                @Override
                public int compare( String o1, String o2 )
                {
                    String[] classAndMethod1 = getClassAndMethod( o1 );
                    String className1 = classAndMethod1[0];
                    String methodName1 = classAndMethod1[1];
                    String[] classAndMethod2 = getClassAndMethod( o2 );
                    String className2 = classAndMethod2[0];
                    String methodName2 = classAndMethod2[1];
                    return testOrderComparator( className1, className2, methodName1, methodName2 );
                }
            };
        }
        else
        {
            return null;
        }
    }

    private void orderTestClasses( List<Class<?>> testClasses, RunOrder runOrder )
    {
        if ( RunOrder.TESTORDER.equals( runOrder ) )
        {
            if ( specifiedRunOrder != null )
            {
                Collections.sort( testClasses, new Comparator<Class<?>>()
                {
                    @Override
                    public int compare( Class<?> o1, Class<?> o2 )
                    {
                        return testOrderComparator( o1.getName(), o2.getName(), null, null );
                    }
                } );
            }
        }
        else if ( RunOrder.RANDOM.equals( runOrder ) )
        {
            Collections.shuffle( testClasses, random );
        }
        else if ( RunOrder.FAILEDFIRST.equals( runOrder ) )
        {
            RunEntryStatisticsMap stat = RunEntryStatisticsMap.fromFile( runOrderParameters.getRunStatisticsFile() );
            List<Class<?>> prioritized = stat.getPrioritizedTestsByFailureFirst( testClasses );
            testClasses.clear();
            testClasses.addAll( prioritized );

        }
        else if ( RunOrder.BALANCED.equals( runOrder ) )
        {
            RunEntryStatisticsMap stat = RunEntryStatisticsMap.fromFile( runOrderParameters.getRunStatisticsFile() );
            List<Class<?>> prioritized = stat.getPrioritizedTestsClassRunTime( testClasses, threadCount );
            testClasses.clear();
            testClasses.addAll( prioritized );

        }
        else if ( sortOrder != null )
        {
            testClasses.sort( sortOrder );
        }
    }

    private static Comparator<Class<?>> getSortOrderComparator( RunOrder runOrder )
    {
        if ( RunOrder.ALPHABETICAL.equals( runOrder ) )
        {
            return getAlphabeticalComparator();
        }
        else if ( RunOrder.REVERSE_ALPHABETICAL.equals( runOrder ) )
        {
            return getReverseAlphabeticalComparator();
        }
        else if ( RunOrder.HOURLY.equals( runOrder ) )
        {
            final int hour = Calendar.getInstance().get( Calendar.HOUR_OF_DAY );
            return ( ( hour % 2 ) == 0 ) ? getAlphabeticalComparator() : getReverseAlphabeticalComparator();
        }
        else
        {
            return null;
        }
    }

    private static Comparator<Class<?>> getReverseAlphabeticalComparator()
    {
        return ( o1, o2 ) -> o2.getName().compareTo( o1.getName() );
    }

    private static Comparator<Class<?>> getAlphabeticalComparator()
    {
        return Comparator.comparing( Class::getName );
    }

    public int testOrderComparator( String className1, String className2, String methodName1, String methodName2 )
    {
        String classFileName1 = toClassFileName( className1 );
        String classFileName2 = toClassFileName( className2 );
        int index1 = -1;
        int index2 = -1;
        if ( specifiedRunOrder != null )
        {
            for ( ResolvedTest filter : specifiedRunOrder )
            {
                if ( filter.matchAsInclusive( classFileName1, methodName1 ) )
                {
                    index1 = specifiedRunOrder.indexOf( filter );
                }
            }
            for ( ResolvedTest filter : specifiedRunOrder )
            {
                if ( filter.matchAsInclusive( classFileName2, methodName2 ) )
                {
                    index2 = specifiedRunOrder.indexOf( filter );
                }
            }
        }
        return index1 - index2;
    }

    public String[] getClassAndMethod( String request )
    {
        String[] classAndMethod = { request, request };
        if ( request.contains( "(" ) )
        {
            String[] nameSplit1 = request.split( "\\(" );
            classAndMethod[0] = nameSplit1[1].substring( 0, nameSplit1[1].length() - 1 );
            classAndMethod[1] = nameSplit1[0];
        }
        return classAndMethod;
    }
}
