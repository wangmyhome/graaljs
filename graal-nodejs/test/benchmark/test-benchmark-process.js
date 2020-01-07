'use strict';

require('../common');

const runBenchmark = require('../common/benchmark');

runBenchmark('process',
             [
               'n=1',
               'type=raw',
               'operation=enumerate',
             ], { NODEJS_BENCHMARK_ZERO_ALLOWED: 1 });
