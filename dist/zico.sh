#!/bin/bash

Z_CMD="$1"

if [ -z "$Z_CMD" ] ; then
    echo "Usage: $0 {start|stop|run|status}"
    exit 1
fi

ZICO_HOME="$(dirname $0)"

if [ -z "$ZICO_HOME" ] ; then
    ZICO_HOME=$PWD
fi

if [[ "$ZICO_HOME" == '.' || $ZICO_HOME =~ ^\.\.?/.* ]] ; then
  ZICO_HOME="$PWD/$ZICO_HOME"
fi

for F in jvm.conf zico.conf zico.jar ; do
  if [ ! -f $ZICO_HOME/$F ] ; then
    echo "Incomplete collector installation: missing $F file in $ZICO_HOME."
  fi
done

for D in log ; do
  if [ ! -d $ZICO_HOME/$D ] ; then
    echo "Missing directory: $ZICO_HOME/$D. Creating..."
    mkdir $ZICO_HOME/$D
  fi
done

. $ZICO_HOME/jvm.conf

if [ -z "$JAVA_HOME" ] ; then
  echo "Missing JAVA_HOME setting. Add JAVA_HOME=/path/to/jdk8 to $ZICO_HOME/jvm.conf."
  exit 1
fi

if [ -z "$Z_NAME" ] ; then
  Z_NAME="zico"
fi

status() {
    pgrep -f "Dzico.app=$Z_NAME" >/dev/null
}

start() {
    if status ; then
      echo "Collector is running."
    else
      echo -n "Starting collector ..."
      cd $ZICO_HOME
      echo "$(date) starting ZICO ..." > $ZICO_HOME/log/console.log
      setsid $JAVA_HOME/bin/java -Dzico.app=$Z_NAME -Dzico.home=$ZICO_HOME $JAVA_OPTS -jar zico.jar >>$ZICO_HOME/log/console.log 2>&1 &
      echo "OK."
    fi
}

run() {
    if status ; then
      echo "Another collector instance is running."
    else
      echo "Starting collector at $ZICO_HOME"
      cd $ZICO_HOME
      $JAVA_HOME/bin/java -Dzico.app=$Z_NAME $JAVA_OPTS -jar zico.jar
    fi
}

stop() {
    if status ; then
      echo -n "Stopping collector ..."
      pkill -f Dzico.app=$Z_NAME >/dev/null
      echo "OK"
    else
      echo -n "Collector already stopped."
    fi
}

case "$Z_CMD" in
start)
    start
    ;;
stop)
    stop
    ;;
run)
    run
    ;;
status)
    if status ; then
      echo "Collector is running."
      exit 0
    else
      echo "Collector is not running."
      exit 1
    fi
    ;;
esac

