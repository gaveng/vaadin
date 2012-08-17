/* 
@VaadinApache2LicenseForJavaFiles@
 */

package com.vaadin.terminal.gwt.server;

import java.lang.reflect.Constructor;
import java.util.Iterator;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.logging.Logger;

import com.vaadin.terminal.DeploymentConfiguration;

public abstract class AbstractDeploymentConfiguration implements
        DeploymentConfiguration {

    private final Class<?> systemPropertyBaseClass;
    private final Properties applicationProperties = new Properties();
    private VaadinContext vaadinContext;
    private boolean productionMode;
    private boolean xsrfProtectionEnabled;
    private int resourceCacheTime;

    public AbstractDeploymentConfiguration(Class<?> systemPropertyBaseClass) {
        this.systemPropertyBaseClass = systemPropertyBaseClass;

        checkProductionMode();
        checkXsrfProtection();
        checkResourceCacheTime();
    }

    @Override
    public String getApplicationOrSystemProperty(String propertyName,
            String defaultValue) {

        String val = null;

        // Try application properties
        val = getApplicationProperty(propertyName);
        if (val != null) {
            return val;
        }

        // Try system properties
        val = getSystemProperty(propertyName);
        if (val != null) {
            return val;
        }

        return defaultValue;
    }

    /**
     * Gets an system property value.
     * 
     * @param parameterName
     *            the Name or the parameter.
     * @return String value or null if not found
     */
    protected String getSystemProperty(String parameterName) {
        String val = null;

        String pkgName;
        final Package pkg = systemPropertyBaseClass.getPackage();
        if (pkg != null) {
            pkgName = pkg.getName();
        } else {
            final String className = systemPropertyBaseClass.getName();
            pkgName = new String(className.toCharArray(), 0,
                    className.lastIndexOf('.'));
        }
        val = System.getProperty(pkgName + "." + parameterName);
        if (val != null) {
            return val;
        }

        // Try lowercased system properties
        val = System.getProperty(pkgName + "." + parameterName.toLowerCase());
        return val;
    }

    @Override
    public ClassLoader getClassLoader() {
        final String classLoaderName = getApplicationOrSystemProperty(
                "ClassLoader", null);
        ClassLoader classLoader;
        if (classLoaderName == null) {
            classLoader = getClass().getClassLoader();
        } else {
            try {
                final Class<?> classLoaderClass = getClass().getClassLoader()
                        .loadClass(classLoaderName);
                final Constructor<?> c = classLoaderClass
                        .getConstructor(new Class[] { ClassLoader.class });
                classLoader = (ClassLoader) c
                        .newInstance(new Object[] { getClass().getClassLoader() });
            } catch (final Exception e) {
                throw new RuntimeException(
                        "Could not find specified class loader: "
                                + classLoaderName, e);
            }
        }
        return classLoader;
    }

    /**
     * Gets an application property value.
     * 
     * @param parameterName
     *            the Name or the parameter.
     * @return String value or null if not found
     */
    protected String getApplicationProperty(String parameterName) {

        String val = applicationProperties.getProperty(parameterName);
        if (val != null) {
            return val;
        }

        // Try lower case application properties for backward compatibility with
        // 3.0.2 and earlier
        val = applicationProperties.getProperty(parameterName.toLowerCase());

        return val;
    }

    @Override
    public Properties getInitParameters() {
        return applicationProperties;
    }

    @Override
    public Iterator<VaadinContextListener> getContextListeners() {
        // Called once for init and once for destroy, so it's probably not worth
        // the effort caching the ServiceLoader instance
        ServiceLoader<VaadinContextListener> contextListenerLoader = ServiceLoader
                .load(VaadinContextListener.class, getClassLoader());
        return contextListenerLoader.iterator();
    }

    @Override
    public void setVaadinContext(VaadinContext vaadinContext) {
        this.vaadinContext = vaadinContext;
    }

    @Override
    public VaadinContext getVaadinContext() {
        return vaadinContext;
    }

    @Override
    public boolean isProductionMode() {
        return productionMode;
    }

    @Override
    public boolean isXsrfProtectionEnabled() {
        return xsrfProtectionEnabled;
    }

    @Override
    public int getResourceCacheTime() {
        return resourceCacheTime;
    }

    /**
     * Log a warning if Vaadin is not running in production mode.
     */
    private void checkProductionMode() {
        productionMode = getApplicationOrSystemProperty(
                Constants.SERVLET_PARAMETER_PRODUCTION_MODE, "false").equals(
                "true");
        if (!productionMode) {
            getLogger().warning(Constants.NOT_PRODUCTION_MODE_INFO);
        }
    }

    /**
     * Log a warning if cross-site request forgery protection is disabled.
     */
    private void checkXsrfProtection() {
        xsrfProtectionEnabled = getApplicationOrSystemProperty(
                Constants.SERVLET_PARAMETER_DISABLE_XSRF_PROTECTION, "false")
                .equals("true");
        if (!xsrfProtectionEnabled) {
            getLogger().warning(Constants.WARNING_XSRF_PROTECTION_DISABLED);
        }
    }

    /**
     * Log a warning if resource cache time is set but is not an integer.
     */
    private void checkResourceCacheTime() {
        try {
            resourceCacheTime = Integer
                    .parseInt(getApplicationOrSystemProperty(
                            Constants.SERVLET_PARAMETER_RESOURCE_CACHE_TIME,
                            "3600"));
        } catch (NumberFormatException e) {
            getLogger().warning(
                    Constants.WARNING_RESOURCE_CACHING_TIME_NOT_NUMERIC);
            resourceCacheTime = 3600;
        }
    }

    private Logger getLogger() {
        return Logger.getLogger(getClass().getName());
    }
}
