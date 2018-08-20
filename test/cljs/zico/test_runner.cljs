(ns zico.test-runner
  (:require
    [doo.runner :refer-macros [doo-tests]]
    [zico.state-mgmt-evt-test]))

(enable-console-print!)

(doo-tests 'zico.state-mgmt-evt-test)


