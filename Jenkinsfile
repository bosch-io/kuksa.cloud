/*********************************************************************
 * Copyright (c) 2019 Bosch Software Innovations GmbH [and others]
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 **********************************************************************/

pipeline {
    agent any
    stages {
        stage ("Test") {
            steps {
                git 'https://github.com/eclipse/kuksa.cloud.git'
                sh """echo 'Hello World' && ls -lah"""
            }
        }
    }
    post {
        failure {
            /* mail to: 'kuksa-dev@eclipse.org',
                subject: "Failed Jenkins Pipeline: ${currentBuild.fullDisplayName}",
                body: "Something is wrong with ${env.BUILD_URL}" */
        }
    }
}
