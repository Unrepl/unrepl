package unrepl;

import clojure.lang.IFn;

public class ClassLoader extends java.lang.ClassLoader {

    final IFn f;
    
    public ClassLoader(java.lang.ClassLoader parent, IFn f) {
        super(parent);
        this.f = f;
    }

    protected Class findClass(String name) throws ClassNotFoundException {
        Class clazz = (Class) f.invoke(name);
        return clazz != null ? clazz : super.findClass(name);
    }

    
}
