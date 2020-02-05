#!/bin/env python

from __future__ import absolute_import
from itertools import chain
from operator import itemgetter
import logging
from hashlib import md5
from six.moves import zip
from yaml import safe_dump

from .formatters.runners import get_formatter as get_runner_formatter
from .formatters.threads import get_formatter as get_threads_formatter
from ..categories import apply_default_categories
from ..options.defaults import apply_default_options
from ..options.globals import apply_global_options, GLOBAL_OPTIONS
from ..options.normalize import normalize
from ..parser import stdci_parse


logger = logging.getLogger(__name__)


class ConfigurationNotFound(Exception):
    pass


class FormatterNotFoundError(Exception):
    pass


class UnknownFileSource(Exception):
    pass


class NoThreadForEnv(Exception):
    pass


class RuntimeEnvDefinition():
    def __init__(self, project, stage, substage, distro, arch):
        all_threads = stdci_parse(project)
        threads_with_default_categories = \
            apply_default_categories(all_threads, stage)
        thread = (
            thread for thread in threads_with_default_categories if
            thread.stage == stage and thread.substage == substage and
            thread.distro == distro and thread.arch == arch
        )
        thread_with_defaults = apply_default_options(thread)
        thread = next(normalize(project, thread_with_defaults), None)
        if thread is None:
            raise NoThreadForEnv(
                'Could not find thread for requested env: {0}'
                .format((stage, substage, distro, arch))
            )
        options = thread.options
        self.script = options['script']
        self.yumrepos = options['yumrepos']
        self.environment = options['environment']
        self.mounts = options['mounts']
        self.repos = options['repos']
        self.packages = options['packages']
        self.hash = md5(
            str(sorted(str(self.repos+self.packages))).encode('utf-8')
        ).hexdigest()

    def format(self, format_):
        formatter, _, template = format_.partition(':')
        return get_runner_formatter(formatter)(self, template)


def get_threads(project, stage):
    """Parse stdci config for the given project and get relevant thread for the
    given stage.

    :param str project: Path to stdci project's root directory.
    :param str stage:   stdci stage.

    :rtype: Iterator
    :returns: Iterator over JobThread instances for the the current project and
              stage.
    """
    threads_for_stage, _ = get_threads_with_globals(project, stage)
    return threads_for_stage


def disable_runif(threads):
    """Check for runif option and replace 'runif' with 'ignore_runif'
    in options for JobThread

    That's done to disable conditional filtering

    :param Iterable vectors: Iterable of JobThread objects

    :rtype: Generator
    :returns: Generator over JobThread objects
    """
    for thread in threads:
        if 'runif' in thread.options:
            thread.options['ignore_runif'] = thread.options.pop('runif')
        yield thread


def get_threads_with_globals(project, stage, ignore_conditions=False):
    """Parse stdci config for the given project and get relevant thread and
    global config for the given stage.

    :param str project:            Path to stdci project's root directory
    :param str stage:              stdci stage
    :param bool ignore_conditions: If True, ignore 'runif' option

    :rtype:   Tuple
    :returns: Iterator over JobThread instances for the the current project and
              stage and the global config
    """
    logger.info("Generating thread objects for project: %s", project)
    all_threads = stdci_parse(project)
    if ignore_conditions:
        logger.info("Change runif for project: %s", project)
        all_threads = disable_runif(all_threads)
    threads_with_global_options = apply_global_options(all_threads)
    sample = next(threads_with_global_options)
    threads_with_global_options = chain([sample], threads_with_global_options)
    sample = next(apply_default_options([sample]))
    global_options = dict(
        zip(GLOBAL_OPTIONS, itemgetter(*GLOBAL_OPTIONS)(sample.options))
    )
    threads_for_current_stage = (
        thread for thread in threads_with_global_options
        if thread.stage is None or thread.stage == stage
    )
    threads_with_default_categories = \
        apply_default_categories(threads_for_current_stage, stage)
    threads_with_default_options = \
        apply_default_options(threads_with_default_categories)
    return normalize(project, threads_with_default_options), global_options


def get_formatted_threads(fmt, project, stage, ignore_conditions=False):
    """Generate stdci thread objects for a given stage and format the data

    :param str fmt:     Points separated string where the first part is the
                        name of the formatter and the second part is a template
                        or argument for the formatter (second part is optional)
                        Example: "my_formatter:{{ t1 }}.{{ t2 }}"
    :param str project: Path to STDCI project's root directory.
    :param str stage:   STDCI stage.
    :param bool ignore_conditions: If True, ignore 'runif' option
    """
    fmt_name, _, template = fmt.partition(':')
    formatter = get_threads_formatter(fmt_name)
    if formatter is None:
        raise FormatterNotFoundError(
            'Could not resolve formatter name {0}.'.format(fmt_name)
        )
    threads, global_cfg = get_threads_with_globals(project,stage,
                                                   ignore_conditions)
    return formatter(threads, global_cfg, template)


def setupLogging(level=logging.INFO):
    """Basic logging setup for users of this script who don't what to bother
    with it
    :param int level: The logging level to setup (set to consts from the
                      logging module, default is INFO)
    """
    logging.basicConfig()
    logging.getLogger().level = level
