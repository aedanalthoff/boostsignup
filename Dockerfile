# ---- Stage 1: compile the servlet from source ----
FROM tomcat:9.0-jdk21-temurin AS build
WORKDIR /build
COPY src/main/java ./java
COPY src/main/webapp ./webapp
RUN mkdir -p webapp/WEB-INF/classes \
 && javac --release 21 -encoding UTF-8 \
      -cp "$CATALINA_HOME/lib/servlet-api.jar:webapp/WEB-INF/lib/*" \
      -d webapp/WEB-INF/classes $(find java -name '*.java') \
 && find webapp -name .DS_Store -delete

# ---- Stage 2: run it on Tomcat 9 (javax.servlet) ----
FROM tomcat:9.0-jre21-temurin
# Deployed as ROOT so the site lives at "/" instead of "/Boostsignup/"
COPY --from=build /build/webapp $CATALINA_HOME/webapps/ROOT
