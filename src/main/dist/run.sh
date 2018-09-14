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
pwd
DB_OPTS="-Dspring.config=$APPDIR/../properties/default_db.xml"
LOG4J_OPTS="-Dlog4j.configuration=file://$APPDIR/properties/log4j.properties"
export GO_AANNOTATION_OPTS="$DB_OPTS $LOG4J_OPTS"
bin/$APPNAME "$@"
