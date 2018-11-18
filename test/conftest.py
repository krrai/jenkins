#!/usr/bin/env python
"""conftest.py - Common pytest fixtures
"""
from __future__ import absolute_import, print_function
import pytest
from six import iteritems
from collections import namedtuple
from functools import partial
try:
    from subprocess import check_output, STDOUT
except ImportError:
    from subprocess import STDOUT, Popen, CalledProcessError, PIPE

    # Backport check_output for EL6
    def check_output(*popenargs, **kwargs):
        if 'stdout' in kwargs:
            raise ValueError('stdout argument not allowed.')
        process = Popen(stdout=PIPE, *popenargs, **kwargs)
        output, unused_err = process.communicate()
        retcode = process.poll()
        if retcode:
            cmd = kwargs.get("args")
            if cmd is None:
                cmd = popenargs[0]
            raise CalledProcessError(retcode, cmd)
        return output


@pytest.fixture
def jenkins_env(monkeypatch, tmpdir):
    env_spec = dict(
        job_base_name='some_job',
        worspace=tmpdir,
    )
    for var, value in iteritems(env_spec):
        monkeypatch.setenv(var.upper(), value)
    monkeypatch.chdir(env_spec['worspace'])
    return namedtuple('jenkins_env_spec', env_spec.keys())(*env_spec.values())


@pytest.fixture
def not_jenkins_env(monkeypatch):
    monkeypatch.delenv('JOB_BASE_NAME', False)


@pytest.fixture
def git():
    def _git(*args, **kwargs):
        git_command = ['git']
        git_command.extend(args)

        stderr = (STDOUT if kwargs.get('append_stderr', False) else None)
        std_out = check_output(git_command, stderr=stderr)

        return std_out.decode('utf-8')

    return _git


@pytest.fixture
def git_at(git):
    def _git_at(path):
        return partial(
            git,
            '--git-dir={0}'.format(path / '.git'),
            '--work-tree={0}'.format(str(path))
        )

    return _git_at


@pytest.fixture
def git_config_at(git):
    def _git_config_at(path):
        return partial(
            git,
            'config',
            '--file={0}'.format(path / '.git' / 'config')
        )

    return _git_config_at


class SymlinkTo(str):
    pass


@pytest.fixture
def symlinkto():
    return SymlinkTo


@pytest.fixture
def gitrepo(tmpdir, git, git_at, git_config_at, symlinkto):
    def repo_maker(reponame, *commits):
        repodir = tmpdir / reponame
        repogit = git_at(repodir)
        git('init', str(repodir))
        repoconfig = git_config_at(repodir)
        repoconfig('user.name', 'test user')
        repoconfig('user.email', 'test@example.com')
        for i, commit in enumerate(commits):
            for fname, fcontents in iteritems(commit.get('files', {})):
                file = (repodir / fname)
                if fcontents is None:
                    if file.exists():
                        repogit('rm', fname)
                    continue
                if isinstance(fcontents, SymlinkTo):
                    if file.check(link=1):
                        repogit('rm', fname)
                    file.mksymlinkto(fcontents)
                else:
                    file.write(fcontents, ensure=True)
                repogit('add', fname)
            msg = commit.get('msg', "Commit #{0}".format(i))
            repogit('commit', '-m', msg, '--allow-empty')
        return repodir
    return repo_maker


@pytest.fixture
def git_last_sha(git_at):
    def _git_last_sha(repo_path):
        return git_at(repo_path)('log', '--format=format:%H', '-1').rstrip()
    return _git_last_sha


@pytest.fixture
def git_status(git_at):
    def _git_status(repo_path):
        return git_at(repo_path)('status', '--short', '--porcelain').rstrip()
    return _git_status
