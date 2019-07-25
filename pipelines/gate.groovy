// gate.groovy - System patch gating job

def on_load(loader){
    // Copy methods from loader to this script
    metaClass.run_jjb_script = { ...args ->
        loader.metaClass.invokeMethod(loader, 'run_jjb_script', args)
    }
    metaClass.checkout_jenkins_repo = { ...args ->
        loader.metaClass.invokeMethod(loader, 'checkout_jenkins_repo', args)
    }
    // Need to specify positional arguments explicitly due to a bug in Jenkins
    // where ...args syntax passes only the 1st argument.
    metaClass.checkout_repo = {
        repo_name, refspec='heads/refs/master', url=null, head=null, clone_dir_name=null ->
        loader.metaClass.invokeMethod(
            loader, 'checkout_repo',
            [repo_name, refspec, url, head, clone_dir_name])
    }
    metaClass.load_code = {
        code_file, load_as=null -> loader.metaClass.invokeMethod(
            loader, 'load_code', [code_file, load_as]
        )
    }
    hook_caller = loader.load_code('libs/stdci_hook_caller.groovy', this)
    hook_caller = loader.hook_caller
    project_lib = loader.load_code('libs/stdci_project.groovy', this)
    stdci_runner_lib = loader.load_code('libs/stdci_runner.groovy', this)
}

def loader_main(loader) {
    stage('Analyzing patches') {
        // Global Var.
        build_thread_params = get_build_thread_parameters()
        system_test_project = project_lib.new_project(
                name: env.SYSTEM_TESTS_PROJECT,
            )
        println("System tests project: ${system_test_project.name}")
        // Global Var.
        available_suits = get_all_suits(system_test_project)
        def available_suits_list = "Found ${available_suits.size()} test suit(s):"
        available_suits_list += available_suits.collect { "\n- ${it}" }.join()
        print(available_suits_list)
        def build_list = "Will run ${build_thread_params.size()} build(s):"
        build_list += build_thread_params.collect { "\n- ${it[2]}" }.join()
        print(build_list)
    }
}

def main() {
    stage('Building packages') {
        def threads = [:]
        releases_to_test = [:]
        threads = create_build_threads(build_thread_params, releases_to_test)
        parallel threads
    }
    stage('Running test suits') {
        def releases_list = "Will test the following releases and builds:"
        releases_list += releases_to_test.collect { release, builds ->
            def builds_list = builds.collect { "\n  - ${it}" }.join()
            "\n- ${release}:${builds_list}"
        }.join()
        print(releases_list)
        run_test_threads(releases_to_test, available_suits)
    }
}

def create_build_threads(build_thread_params, releases_to_test) {
    def threads = [:]
    for (i = 0; i < build_thread_params.size(); ++i) {
        // build_thread_params have 3 elements inside the list by order of: job
        // run specs for jenkins, ovirt-releases and unique job name to display
        def job_run_spec = build_thread_params[i][0]
        def releases = build_thread_params[i][1]
        def thread_name = build_thread_params[i][2]
        threads[thread_name] = create_build_thread(
            job_run_spec, releases, releases_to_test
        )
    }
    return threads
}

def create_build_thread(job_run_spec, releases, releases_to_test) {
    return {
        job_run_spec['wait'] = true
        build_results = build(job_run_spec)
        releases.each { release ->
            def temp_url = releases_to_test.get(release, [])
            temp_url << build_results.absoluteUrl
            releases_to_test[release] = temp_url
        }
    }
}

def get_build_thread_parameters() {
    def build_thread_params = "build_thread_params_for_gating.json"
    withEnv(['PYTHONPATH=jenkins']) {
        sh """\
            #!/usr/bin/env python
            import json
            from os import environ
            from scripts.ost_build_resolver import create_patch_threads
            jobs = create_patch_threads(
                environ['CHECKED_COMMITS'],
                environ['SYSTEM_QUEUE_PREFIX'],
            )

            with open('${build_thread_params}', 'w') as jtb:
                json.dump(jobs, jtb)
        """.stripIndent()
    }
    def jobs = readJSON file: build_thread_params
    return jobs
}

@NonCPS
def get_test_threads(releases_to_test, available_suits) {
    def suit_types_to_use = (env?.SYSTEM_TEST_SUIT_TYPES ?: 'basic').tokenize()
    return releases_to_test.collectMany { release, builds ->
        suit_types_to_use.findResults { suit_type ->
            // script has to be of type `String` so looking it up in the
            // `available_suits` Set will work
            String script = "${suit_type}_suite_${release}.sh"
            if(script in available_suits) {
                def extra_sources = builds.collect { "jenkins:${it}"}.join('\n')
                return [
                    'stage': "${suit_type}-suit-${release}",
                    'substage': 'default',
                    'distro': 'el7',
                    'arch': 'x86_64',
                    'script': "automation/$script",
                    'runtime_reqs': [
                        'supportnestinglevel': 2,
                        'isolationlevel' : 'container'
                    ],
                    'release_branches': [:],
                    'reporting': ['style': 'stdci'],
                    'timeout': '3h',
                    'extra_sources' : extra_sources
                ]
            }
        }
    }
}

def run_test_threads(releases_to_test, available_suits) {
    def test_threads = get_test_threads(releases_to_test, available_suits)
    def threads_list = "Will run the following test suits:"
    threads_list += test_threads.collect { "\n - ${it.stage}" }.join()
    print(threads_list)
    stdci_runner_lib.run_std_ci_jobs(
        project: system_test_project,
        jobs: test_threads
    )
}

def get_all_suits(system_test_project) {
    project_lib.checkout_project(system_test_project)
    def all_suits
    dir(system_test_project.name) {
        all_suits = findFiles(glob: 'automation/*_suite_*.sh').name as Set
    }
    return all_suits
}

// We need to return 'this' so the actual pipeline job can invoke functions from
// this script
return this