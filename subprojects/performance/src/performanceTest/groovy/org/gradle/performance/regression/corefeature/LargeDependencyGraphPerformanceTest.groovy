/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.performance.regression.corefeature

import org.gradle.performance.AbstractCrossVersionPerformanceTest
import org.gradle.performance.WithExternalRepository
import spock.lang.Unroll

class LargeDependencyGraphPerformanceTest extends AbstractCrossVersionPerformanceTest implements WithExternalRepository {

    private final static TEST_PROJECT_NAME = 'excludeRuleMergingBuild'

    def setup() {
        runner.minimumVersion = '4.0'
        runner.targetVersions = ["4.7-20180308002700+0000"]
    }

    def "resolve large dependency graph from file repo"() {
        runner.testProject = TEST_PROJECT_NAME

        given:
        runner.tasksToRun = ['resolveDependencies']
        runner.gradleOpts = ["-Xms256m", "-Xmx256m"]
        runner.args = ["-PnoExcludes"]

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()
    }

    @Unroll
    def "resolve large dependency graph (improvedPomSupport = #improvedPomSupport, parallel = #parallel)"() {
        runner.testProject = TEST_PROJECT_NAME
        startServer()

        given:
        runner.tasksToRun = ['resolveDependencies']
        runner.gradleOpts = ["-Xms256m", "-Xmx256m"]
        runner.args = ['-PuseHttp', "-PhttpPort=${serverPort}", '-PnoExcludes']
        if (parallel) {
            runner.args += '--parallel'
        }
        if (improvedPomSupport) {
            runner.args += '-Porg.gradle.advancedpomsupport=true -PimprovedPomSupport=true'
        }

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        cleanup:
        stopServer()

        where:
        parallel | improvedPomSupport
        true     | true
        true     | false
        false    | true
        false    | false
    }

}
