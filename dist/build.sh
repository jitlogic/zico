#!/bin/bash

export JAVA_HOME=/opt/jdk8
export PATH=$JAVA_HOME/bin:$PATH

cd ..

ZV=$(head -1 project.clj | awk '{print $3}' | tr -d '"')

echo "Building ZICO v$ZV ..."

lein do clean, sass once, uberjar || exit 1

cd target

ZN=zico-$ZV

mkdir $ZN
mv zico.jar $ZN/
cp ../dist/* $ZN/
cp ../resources/zico/zico.edn $ZN/
rm $ZN/build.sh
cp ../COPYING ../CHANGES.md $ZN/

zip -r $ZN.zip $ZN
