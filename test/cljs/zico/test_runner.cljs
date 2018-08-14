(ns zico.test-runner
  (:require
    [doo.runner :refer-macros [doo-tests]]
    [zico.test-state]))

(enable-console-print!)

(doo-tests 'zico.test-state)


