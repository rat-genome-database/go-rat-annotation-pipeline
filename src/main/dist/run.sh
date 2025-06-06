#!/usr/bin/env bash
#
# download and extract data from GOA_RAT ftp site
# non-RGD annotations are loaded into database
# File "data/goa_rgd.txt" is created in the end with all of the annotations
#    that had not been loaded into RGD database (duplicates and not matching RGD genes)
. /etc/profile

APPNAME="go-rat-annotation-pipeline"
APPDIR=/home/rgddata/pipelines/$APPNAME

SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`
if [ "$SERVER" == "REED" ]; then
  EMAILLIST="rgd.devops@mcw.edu jrsmith@mcw.edu slaulederkind@mcw.edu"
else
  EMAILLIST=mtutaj@mcw.edu
fi

cd $APPDIR

java -Dspring.config=$APPDIR/../properties/default_db2.xml \
  -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
  -jar lib/${APPNAME}.jar "$@" > $APPDIR/run.log

mailx -s "[$SERVER] GOA rat annotation pipeline" $EMAILLIST < $APPDIR/logs/GoaSummary.log

