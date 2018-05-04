#!/bin/bash

cd ..

ZV=$(head -1 project.clj | awk '{print $3}' | tr -d '"')

echo "Building ZICO v$ZV ..."

lein do clean, sass once, uberjar || exit 1

cd target

ZN=zico-$ZV

mkdir $ZN
mv zico.jar $ZN/
cp ../dist/* $ZN/
rm $ZN/build.sh

zip -r $ZN.zip $ZN
