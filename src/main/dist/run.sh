#!/usr/bin/env bash
#
# download and extract data from GOA_RAT ftp site
# non-RGD annotations are loaded into database
# File "data/goa_rgd.txt" is created in the end with all of the annotations
#    that had not been loaded into RGD database (duplicates and not matching RGD genes)
. /etc/profile

APPNAME=GOAannotation
APPDIR=/home/rgddata/pipelines/$APPNAME

cd $APPDIR

java -Dspring.config=$APPDIR/../properties/default_db.xml \
  -Dlog4j.configuration=file://$APPDIR/properties/log4j.properties \
  -jar ./$APPNAME.jar "$@"