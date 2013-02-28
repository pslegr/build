Tomcat 6 - Remote
=================

Version: 6.0.33

You need to modify a conf/tomcat-users.xml:

    <tomcat-users>
        <role rolename="manager" />
        <role rolename="manager-jmx" />
        <role rolename="manager-script" />
        <role rolename="manager-gui" />
        <role rolename="manager-status" />
        <role rolename="admin" />
        <user username="admin" password="pass" roles="standard,manager,admin,manager-jmx,manager-gui,manager-script,manager-status" />
    </tomcat-users>


Start the container with following options setup:

    export JAVA_OPTS="-Dcom.sun.management.jmxremote.port=8089 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false"
    ./bin/catalina.sh run
