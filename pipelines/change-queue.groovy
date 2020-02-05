// change-queue.groovy - Jenkins Pipeline script for managing change queues
//
def main() {
    def upstreamBuild = null

    stage('loading queue state') {
        load_queue_state()
        upstreamBuild = get_upstream_build()
        show_upstream_build(upstreamBuild)
    }
    stage('running queue logic') {
        run_queue_action_py(upstreamBuild)
        currentBuild.displayName = get_build_display_name()
        currentBuild.description = get_queue_action_from_log()
        show_queue_status()
    }
    stage('saving artifacts') {
        archive 'exported-artifacts/**'
    }
    if(!fileExists('build_args.json')) {
        return
    }
    stage('triggering test job') {
        build_args = readJSON(file: 'build_args.json')
        build_args['wait'] = false
        build build_args
    }
}

def load_queue_state() {
    dir('exported-artifacts') {
        deleteDir()
        // We touch a file to make sure the exported-artifacts directory gets
        // recreated
        touch file: 'queue-status.html'
    }
    step([
        $class: 'CopyArtifact',
        filter: 'exported-artifacts/JenkinsChangeQueue.dat',
        fingerprintArtifacts: true,
        projectName: env.JOB_NAME,
        selector: [$class: 'StatusBuildSelector', stable: false],
        optional: true,
    ])
}

def show_upstream_build(upstreamBuild) {
    if(upstreamBuild == null) {
        return
    }
    if(upstreamBuild.job_name == "${env.JOB_NAME}-tester") {
        manager.addShortText(
            "<a href=\"${env.JENKINS_URL}${upstreamBuild.build_url}\">" +
            "<img src=\"/images/16x16/gear2.png\">" +
            "test #${upstreamBuild.build_number}</a>",
            'black', 'white', 'none', ''
        )
    }
}

def run_queue_action_py(upstreamBuild) {
    withEnv(['PYTHONPATH=jenkins']) {
        if(upstreamBuild) {
            upstreamBuild = "'${env.JENKINS_URL}${upstreamBuild['build_url']}'"
        } else {
            upstreamBuild = 'None'
        }
        sh """\
            #!/usr/bin/env python
            import sys

            from stdci_libs.change_queue import JenkinsChangeQueue

            JenkinsChangeQueue.setup_logging()
            with JenkinsChangeQueue.persist_in_artifacts() as cq:
                cq.act_on_job_params(
                    '${params.QUEUE_ACTION}', '${params.ACTION_ARG}', ${upstreamBuild}
                )
        """.stripIndent()
    }
}

@NonCPS
def get_build_display_name() {
    def name_from_log = get_queue_action_from_log()
    if(name_from_log) {
        return "${currentBuild.id} ${name_from_log}"
    }
    return currentBuild.id
}

@NonCPS
def get_queue_action_from_log() {
    currentBuild.rawBuild.getLog(50).findResult {
        def match = (it =~ /Queue action: (.+)/)
        if(match.asBoolean()) {
            return match[0][1]
        }
    } ?: ''
}

def show_queue_status() {
    def status_file = 'exported-artifacts/queue-status.html'
    if(!fileExists(status_file)) {
        echo "Queue status file not found"
        return
    }
    echo "Showing queue status"
    add_summary('gear2.png', readFile(status_file))
}

@NonCPS
def add_summary(icon, html) {
    def summary = manager.createSummary(icon)
    summary.appendText(html, false)
}

@NonCPS
def get_upstream_build() {
    return currentBuild.rawBuild.getCauses().findResult() {
        if(it instanceof hudson.model.Cause.UpstreamCause) {
            return [
                job_name: it.upstreamProject,
                build_number: it.upstreamBuild,
                description: it.shortDescription,
                jub_url: it.upstreamUrl,
                build_url: it.upstreamRun.url,
            ]
        }
    }
}

// We need to return 'this' so the actual pipeline job can invoke functions from
// this script
return this
