/*
 *  Copyright 2011 Uwe Krueger.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.mandelsoft.mand;

import com.mandelsoft.io.AbstractFile;
import com.mandelsoft.io.FileAbstractFile;
import com.mandelsoft.io.URLAbstractFile;
import com.mandelsoft.mand.util.MandelList;
import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import com.mandelsoft.mand.scan.MandelScanner;
import com.mandelsoft.mand.cm.Colormap;
import com.mandelsoft.mand.cm.ColormapModel;
import com.mandelsoft.mand.cm.ColormapModel.ResizeMode;
import com.mandelsoft.mand.image.MandelAreaImage;
import com.mandelsoft.mand.image.MandelImage;
import com.mandelsoft.mand.mapping.Mapper;
import com.mandelsoft.mand.scan.ContextMandelScanner;
import com.mandelsoft.mand.scan.DistributedMandelScanner;
import com.mandelsoft.mand.scan.MandelFolder;
import com.mandelsoft.mand.scan.MandelHandle;
import com.mandelsoft.mand.scan.MandelScannerProxy;
import com.mandelsoft.mand.scan.MandelScannerUtils;
import com.mandelsoft.mand.util.ColorList;
import com.mandelsoft.mand.util.ColormapList;
import com.mandelsoft.mand.util.DeltaMandelList;
import com.mandelsoft.mand.util.MandUtils;
import com.mandelsoft.mand.util.MandelListFolderTree;
import com.mandelsoft.mand.util.MemoryMandelListFolderTree;
import com.mandelsoft.mand.util.ScannerColormapList;
import com.mandelsoft.mand.util.TagList;
import com.mandelsoft.mand.util.UniqueArrayMandelList;
import com.mandelsoft.util.Utils;

/**
 *
 * @author Uwe Krueger
 */
public class Environment1 implements MandelConstants  {
  static public boolean debug=false;

  public static class FileInfo {
    private Colormap cm;

    public Colormap getColormap()
    {
      return cm;
    }

    public void setColormap(Colormap cm)
    {
      this.cm=cm;
    }
  }

  private MandelImageDBFactory dbfactory;
  private MandelImageDBContext context;
  private MandelImageDB        database;

  private MandelImage.Factory fac;
  private MandelListFolderTree memory;

  private QualifiedMandelName initialName;
  private File                initialFile;

  private MandelScanner autoimagedata;
  private MandelScanner autometa;
  
  private MandelList unseenrasters;
  private ColormapList colormaplist;
  private Colormap defcolormap;
  
  private boolean autoRescan=true;

  private Proxy proxy=null;

  private Environment1(String tool)
  {
    dbfactory=new MandelImageDBFactory(tool);
    memory=new MemoryMandelListFolderTree("memory");
  }

  public Environment1(String tool, String[] args) throws IllegalConfigurationException
  { this(tool,args,new File("."));
  }

  public Environment1(String[] args) throws IllegalConfigurationException
  { this(args,new File("."));
  }

  public Environment1(String[] args, File dir) throws IllegalConfigurationException
  {
    this(null,args,dir);
  }

  public Environment1(String tool, String[] args, File dir) throws IllegalConfigurationException
  {
    this(tool);
    
    if (args==null || args.length<1) {
      initialName=new QualifiedMandelName(MandelName.ROOT);
    }
    else {
      File f=new File(args[0]);
      if (f.exists() && !f.isDirectory()) initialFile=f;
      else {
        if (f.isDirectory()) {
          dir=f;
          initialName=new QualifiedMandelName(MandelName.ROOT);
        }
        else {
          initialName=QualifiedMandelName.create(f);
        }
      }
    }

    context=dbfactory.get(new FileAbstractFile(dir));

    commonSetup();
    if (database.getSeenRasters()!=null)
      unseenrasters=new DeltaMandelList(getImageDataScanner(),getSeenRasters());
  }

  public Environment1(String tool, String[] args, URL dir) throws IllegalConfigurationException
  {
    this(tool);

    if (args==null || args.length<1) {
      initialName=new QualifiedMandelName(MandelName.ROOT);
    }
    else {
      initialName=QualifiedMandelName.create(dir.getPath());
    }

    context=dbfactory.get(new URLAbstractFile(null,dir));
   
    commonSetup();
  }

  private void commonSetup() throws IllegalConfigurationException
  {
    fac=new MandelImage.Factory();

    context.complete();
    System.out.println("Image Base Layout:");
    context.print(System.out,"  ");
    database=context.getDatabase();
    String s=database.getProperty(Settings.SITE);
    if (!Utils.isEmpty(s)) System.out.println("Site name : "+s);
    s=database.getProperty(Settings.USER);
    if (!Utils.isEmpty(s)) System.out.println("Site owner: "+s);

    
    autoRescan=database.getSettings().getSwitch(Settings.AUTORESCAN,true);
    setupScanners();
    autoimagedata=new AutoScanner(getImageDataScanner());
    autometa=new AutoScanner(getMetaScanner());
    setupDerivedLists();

    colormaplist=new ScannerColormapList(getColormapScanner());
    String cmname=database.getProperty(Settings.DEFCOLORMAP);
    System.out.println("default colormap property: "+cmname);
    if (cmname!=null && colormaplist!=null) {
      try {
        defcolormap=colormaplist.get(new ColormapName(cmname));
        if (defcolormap==null) {
          System.err.println("colormap "+cmname+" not found");
        }
      }
      catch (IOException io) {
        System.err.println("cannot read colormap "+cmname);
      }
    }
    else {
      System.out.println("no default colormap");
    }
  }
  
  public boolean isAutoRescan()
  { return autoRescan;
  }

  public void setAutoRescan(boolean b)
  { autoRescan=b;
  }

  public void refresh(MandelList list)
  { boolean save=autoRescan;
    autoRescan=true;
    list.refresh(false);
    autoRescan=save;
  }

  public void autoRescan()
  {
    if (isAutoRescan()) rescan();
  }

  public void autoRescan(boolean verbose)
  {
    if (isAutoRescan()) rescan(verbose);
  }

  public void rescan()
  { rescan(false);
  }

  public void rescan(boolean verbose)
  { 
    database.rescan(verbose);
  }

  public QualifiedMandelName getInitialName()
  {
    return initialName;
  }

  public File getInitialFile()
  {
    return initialFile;
  }

  public MandelImage.Factory getFactory()
  {
    return fac;
  }

  public MandelScanner getAutoImageDataScanner()
  {
    return autoimagedata;
  }

  public MandelScanner getAutoMetaScanner()
  {
    return autometa;
  }
  
  public MandelListFolderTree getMemory()
  { return memory;
  }

  public MandelList getUnseenRasters()
  {
    return unseenrasters;
  }

  public ColormapList getColormaps()
  {
    return colormaplist;
  }

  public Colormap getDefaultColormap()
  {
    return defcolormap;
  }
  
  public boolean isReadonly(String label)
  {
    if (label==null) return isReadonly();
    MandelImageDBContext ctx=context.getContext(label);
    return ctx==null||ctx.getDatabase().isReadonly();
  }
  
  ////////////////////////////////////////////////////////////////////////////
  public boolean isReadonly()
  {
    return database.isReadonly();
  }

  public boolean isCleanupRasterImage()
  {
    return database.isCleanupRasterImage();
  }

  public boolean isCleanupRaster()
  {
    return database.isCleanupRaster();
  }

  public boolean isCleanupInfo()
  {
    return database.isCleanupInfo();
  }

  public List<MandelListFolderTree> getUserLists()
  {
    return database.getUserLists();
  }

  public MandelListFolderTree getTodos()
  {
    return database.getTodos();
  }

  public MandelListFolderTree getLinks()
  {
    return database.getLinks();
  }

  public TagList getTags()
  {
    return database.getTags();
  }

  public MandelList getSeenRasters()
  {
    return database.getSeenRasters();
  }

  public File getRasterImageFolder(AbstractFile d)
  {
    return database.getRasterImageFolder(d);
  }

  public File getRasterFolder(AbstractFile d)
  {
    return database.getRasterFolder(d);
  }

  public Proxy getProxy()
  {
    return database.getProxy();
  }

  public String getProperty(String name)
  {
    return database.getProperty(name);
  }

  public MandelList getMainTodos()
  {
    return database.getMainTodos();
  }

  public MandelList getMainFavorites()
  {
    return database.getMainFavorites();
  }

  public File getInfoFolder(AbstractFile d)
  {
    return database.getInfoFolder(d);
  }

  public File getImageFolder(AbstractFile d)
  {
    return database.getImageFolder(d);
  }

  protected File getFolder(AbstractFile mf, String prop)
  {
    return database.getFolder(mf, prop);
  }

  public MandelListFolderTree getFavorites()
  {
    return database.getFavorites();
  }

  public String getCopyright()
  {
    return database.getCopyright();
  }

  public String getCopyright(String creator, String site)
  {
    return database.getCopyright(creator, site);
  }

  public String getCopyright(MandelInfo info)
  {
    return database.getCopyright(info);
  }

  public ColorList getColors()
  {
    return database.getColors();
  }

  public MandelList getAreas()
  {
    return database.getAreas();
  }

  public AbstractFile createMandelFile(String path)
  {
    return database.createAbstractFile(path);
  }

  ///////////////////////////////////////////////////////////////////////////

  private MandelScanner all;
  private MandelScanner imagedata;
  private MandelScanner raster;
  private MandelScanner info;
  private MandelScanner prioinfo;
  private MandelScanner meta;
  private MandelScanner rasterimage;
  private MandelScanner colormaps;
  private MandelScanner newraster;

  private MandelScanner createScanner(DistributedMandelScanner.ScannerAccess acc)
  {
    MandelScanner scanner=acc.getScanner(database);
    if (scanner!=null && context.hasNested()) {
      return new DistributedMandelScanner(context, acc);
    }
    else {
      return scanner;
    }
  }

  private void setupScanners()
  {
     all=createScanner(new DistributedMandelScanner.ScannerAccess() {
       public MandelScanner getScanner(MandelImageDB db)
       {
         return db.getAllScanner();
       }
     });

     colormaps=createScanner(new DistributedMandelScanner.ScannerAccess() {
       public MandelScanner getScanner(MandelImageDB db)
       {
         return db.getColormapScanner();
       }
     });


     raster=createScanner(new DistributedMandelScanner.ScannerAccess() {
       public MandelScanner getScanner(MandelImageDB db)
       {
         return db.getRasterScanner();
       }
     });

     rasterimage=createScanner(new DistributedMandelScanner.ScannerAccess() {
       public MandelScanner getScanner(MandelImageDB db)
       {
         return db.getRasterImageScanner();
       }
     });

     prioinfo=createScanner(new DistributedMandelScanner.ScannerAccess() {
       public MandelScanner getScanner(MandelImageDB db)
       {
         return db.getPrioInfoScanner();
       }
     });

     newraster=createScanner(new DistributedMandelScanner.ScannerAccess() {
       public MandelScanner getScanner(MandelImageDB db)
       {
         return db.getNewRasterScanner();
       }
     });

     meta=createScanner(new DistributedMandelScanner.ScannerAccess() {
       public MandelScanner getScanner(MandelImageDB db)
       {
         return db.getMetaScanner();
       }
     });

     info=createScanner(new DistributedMandelScanner.ScannerAccess() {
       public MandelScanner getScanner(MandelImageDB db)
       {
         return db.getInfoScanner();
       }
     });

     imagedata=createScanner(new DistributedMandelScanner.ScannerAccess() {
       public MandelScanner getScanner(MandelImageDB db)
       {
         return db.getImageDataScanner();
       }
     });
  }

  public MandelScanner getAllScanner()
  {
    return all;
  }

  public MandelScanner getColormapScanner()
  {
    return colormaps;
  }

  public MandelScanner getRasterScanner()
  {
    return raster;
  }

  public MandelScanner getRasterImageScanner()
  {
    return rasterimage;
  }

  public MandelScanner getPrioInfoScanner()
  {
    return prioinfo;
  }

  public MandelScanner getNewRasterScanner()
  {
    return newraster;
  }

  public MandelScanner getMetaScanner()
  {
    return meta;
  }

  public MandelScanner getInfoScanner()
  {
    return info;
  }

  public MandelScanner getImageDataScanner()
  {
    return imagedata;
  }

  ///////////////////////////////////////////////////////////////////////////
  // Standard derived lists
  ///////////////////////////////////////////////////////////////////////////

  private MandelList newrasters;
  private MandelList variants;
  private MandelList leafs;
  private MandelList pending;

  public MandelList getNewRasters()
  { return newrasters;
  }

  public MandelList getVariants()
  { return variants;
  }

  public MandelList getLeafs()
  { return leafs;
  }

  public MandelList getPending()
  { return pending;
  }

  private void setupDerivedLists()
  {
     //System.out.println("new rasters");
    if (getNewRasterScanner()!=null) {
      newrasters=new NewRasterList();
    }
    // the following lists should always be there
     //System.out.println("variants");
    variants=new VariantImageList();
    //System.out.println("leafs");
    leafs=new LeafImageList();
    //System.out.println("pending");
    pending=new PendingImageList();
    //System.out.println("areas");
  }

  private class NewRasterList extends ScannerBasedList {
    protected MandelScanner getScanner()
    {
      return getNewRasterScanner();
    }
  }


  ///////////////////////////////////////////////////////////////////////////

  private class VariantImageList extends ScannerBasedList {
    protected MandelScanner getScanner()
    {
      return getImageDataScanner();
    }

    @Override
    protected void _addAll(Set<QualifiedMandelName> set)
    {
      for (QualifiedMandelName n: set) {
        if (n.getQualifier()!=null) add(n);
      }
    }
  }

  private class LeafImageList extends ScannerBasedList {
    protected MandelScanner getScanner()
    {
      return getImageDataScanner();
    }

    @Override
    protected void _addAll(Set<QualifiedMandelName> set)
    {
      if (!set.isEmpty()) {
        MandelName mn=set.iterator().next().getMandelName();
        MandelScanner all=getAllScanner();

        if (!MandUtils.hasSubNames(mn, all)) {
          //  System.out.println("- "+mn+" has no sub areas");
          for (QualifiedMandelName n :set) {
            add(n);
          }
        }
        else {
          // System.out.println("+ "+mn+" has subareas");
        }
      }
    }
  }

  private class PendingImageList extends ScannerBasedList {
    protected MandelScanner getScanner()
    {
      return getImageDataScanner();
    }

    @Override
    protected void _addAll(Set<QualifiedMandelName> set)
    {
      if (!set.isEmpty()) {
        boolean add=false;
        QualifiedMandelName qn=set.iterator().next();
        MandelName mn=qn.getMandelName();
        MandelScanner all=getAllScanner();
        Set<MandelName> sub=MandUtils.getSubNames(mn, all);

        for (MandelName s :sub) {
          Set<MandelHandle> subs=all.getMandelHandles(s);
          if (!MandelScannerUtils.hasImageData(subs)) {
            add=true;
            break;
          }
        }
        if (add) { // add image (select generic name if possible
          for (QualifiedMandelName n :set) {
            if (n.getQualifier()==null) {
              add(n);
              add=false;
              break;
            }
          }
          if (add) add(qn);
        }
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////

  public abstract class ScannerBasedList extends UniqueArrayMandelList {

    ScannerBasedList()
    {
      _refresh();
    }

    abstract protected MandelScanner getScanner();

    @Override
    public void refresh(boolean soft)
    {
      this.clear();
      if (!soft) getScanner().rescan(false);
      _refresh();
    }

    protected void _addAll(Set<QualifiedMandelName> set)
    {
      addAll(set);
    }

    private void _refresh()
    {
      for (MandelName n:getScanner().getMandelNames()) {
        _addAll(getScanner().getQualifiedMandelNames(n));
      }
    }
  }

 ///////////////////////////////////////////////////////////////////////////

 public MandelHandle getMandelImageData(MandelName name)
  {
    QualifiedMandelName qn=new QualifiedMandelName(name);
    MandelHandle h=getImageDataScanner().getMandelData(qn);
    if (h==null)  h=getImageDataScanner().getMandelData(name);
    return h;
  }

  public MandelHandle getMandelImageData(QualifiedMandelName name)
  {
    return getImageDataScanner().getMandelData(name);
  }

  /////////////////////////////////////////////////////////////////////////
  public MandelAreaImage getMandelImage(MandelName name) throws IOException
  { return getMandelImage(name, ResizeMode.RESIZE_PROPORTIONAL,null);
  }

  public MandelAreaImage getMandelImage(MandelName name, ColormapModel cm)
                     throws IOException
  { return getMandelImage(name,cm,null);
  }

  public MandelAreaImage getMandelImage(MandelName name, ResizeMode mode,
                                    Colormap cm)
                     throws IOException
  { return getMandelImage(name,mode,cm,null);
  }

  public MandelAreaImage getMandelImage(MandelName name,
                                    ColormapModel cm, Mapper m)
                     throws IOException
  {
    return getMandelImage(name,cm.getResizeMode(),cm.getColormap(),m);
  }

  public MandelAreaImage getMandelImage(MandelName name, ResizeMode mode,
                                    Colormap cm, Mapper m)
                     throws IOException
  {
    return getMandelImage(name,mode,cm,m,null);
  }

  public MandelAreaImage getMandelImage(MandelName name, ResizeMode mode,
                                    Colormap cm, Mapper m, FileInfo info)
                     throws IOException
  { MandelHandle h=getMandelImageData(name);
    if (h==null) {
      System.err.println("no image data found for "+name);
      return null;
    }
    return getMandelImage(h,mode,cm,m,info);
  }

  /////////////////////////////////////////////////////////////////////////
  public MandelAreaImage getMandelImage(QualifiedMandelName name) throws IOException
  { return getMandelImage(name, ResizeMode.RESIZE_PROPORTIONAL,null);
  }

  public MandelAreaImage getMandelImage(QualifiedMandelName name, ColormapModel cm)
                     throws IOException
  { return getMandelImage(name, cm, null);
  }

  public MandelAreaImage getMandelImage(QualifiedMandelName name, ResizeMode mode,
                                    Colormap cm)
                     throws IOException
  { return getMandelImage(name,mode, cm,null);
  }

  public MandelAreaImage getMandelImage(QualifiedMandelName name, ColormapModel cm,
                                    Mapper m)
                     throws IOException
  { return getMandelImage(name,cm==null?ResizeMode.RESIZE_PROPORTIONAL:
                                        cm.getResizeMode(),
                               cm==null?null:cm.getColormap(), m);
  }

  public MandelAreaImage getMandelImage(QualifiedMandelName name, ResizeMode mode,
                                    Colormap cm, Mapper m)
                     throws IOException
  {
    return getMandelImage(name,mode,cm,m,null);
  }

  public MandelAreaImage getMandelImage(QualifiedMandelName name, ResizeMode mode,
                                    Colormap cm, Mapper m, FileInfo info)
                     throws IOException
  { MandelHandle h=getMandelImageData(name);
    if (h==null) {
      System.err.println("no image data found for "+name);
      return null;
    }
    return getMandelImage(h, mode, cm, m, info);
  }

  /////////////////////////////////////////////////////////////////////////
//  public MandelImage getMandelImage(MandelFileName name,
//                                    ColormapModel cm, Mapper m)
//                     throws IOException
//  {
//    return getMandelImage(name,cm.getResizeMode(),cm.getColormap(),m);
//  }
//
//  public MandelImage getMandelImage(MandelFileName name, ResizeMode mode,
//                                    Colormap cm, Mapper m)
//                     throws IOException
//  {
//    return getMandelImage(name,mode,cm,m,null);
//  }
//
//  public MandelImage getMandelImage(MandelFileName name, ResizeMode mode,
//                                    Colormap cm, Mapper m, FileInfo info)
//                     throws IOException
//  { MandelData md=getMandelImageData(name);
//    if (md==null) {
//      System.err.println("no image data found for "+name);
//      return null;
//    }
//    return getMandelImage(md,mode,cm,m,info);
//  }
    
  public MandelAreaImage getMandelImage(MandelHandle h, ResizeMode mode,
                                    Colormap cm, Mapper m, FileInfo info)
                     throws IOException
  {
    MandelData md=h.getData();
    return getMandelImage(h.getName(),md,mode,cm,m,info);
  }

  public MandelAreaImage getMandelImage(QualifiedMandelName name, MandelData md,
                                    ResizeMode mode,
                                    Colormap cm, Mapper m, FileInfo info)
                     throws IOException
  {
    MandelImage img;
    if (info!=null) {
      info.setColormap(md.getColormap());
    }
    img=getFactory().getImage(md,mode,cm,m);
    if (img!=null) return new MandelAreaImage(name,img);
    return null;
  }

  ///////////////////////////////////////////////////////////////////////////

  public boolean handleRasterSeen(AbstractFile f)
  { String n=getProperty(Settings.RASTER_SAVE_PATH);
    String s=getProperty(Settings.RASTER_SEEN_PATH);
    String v=getProperty(Settings.VARIANT_SEEN_PATH);

    if (f==null || isReadonly()) return false;
    
    QualifiedMandelName mn=QualifiedMandelName.create(f);
    if (unseenrasters!=null && unseenrasters.contains(mn)) {
      if (debug) System.out.println(mn+" set to seen: "+f);
      unseenrasters.remove(mn);
      try {
        unseenrasters.save();
      }
      catch (IOException ex) {
        System.err.println("cannot write seen: "+ex);
      }
      seenModified();
    }
    if (!Utils.isEmpty(mn.getQualifier()) && !Utils.isEmpty(v)) s=v;

    //System.out.println("seen path: "+s);
    if (Utils.isEmpty(s)) return false;
    if (Utils.isEmpty(n)) return false;

    File root=null;
    if (f.isFile()) {
      try {
        root=f.getFile().getParentFile().getCanonicalFile();
      }
      catch (IOException ex) {
        System.err.println("cannot eval "+f);
        return false;
      }

      StringTokenizer t=new StringTokenizer(n, ";:");
      boolean found=false;
      while (t.hasMoreTokens()) {
        try {
          File save=new File(t.nextToken()).getCanonicalFile();
          if (save.equals(root)) {
            found=true;
            break;
          }
        }
        catch (IOException ex) {
         // ignore illegal path
        }
      }
      if (!found) {
        System.err.println("not in save path");
        return false;
      }
      if (debug) System.out.println("relocation candidate");

      File store;
      try {
        store=new File(s).getCanonicalFile();
      }
      catch (IOException ex) {
        return false;
      }
      if (!store.equals(root)&&f.isFile()) {
        File nf=new File(store, f.getName());
        File of=f.getFile();
        try {
          System.out.println("relocate file "+of+" to "+store);
          MandelFolder mf=MandelFolder.getMandelFolder(store);
          if (mf.renameTo(of, nf)) {
            if (of.exists()&&nf.exists()) {
              System.out.println("*** delete "+of);
              of.delete();
            }
            return true;
          }
        }
        catch (IOException ex) {
        }
        //if (isAutoRescan()) newraster.rescan(false);
        //else addLogicalFile(nf);
      }
    }
    return false;
  }

  ////////////////////////////////////////////////////////////////////////////
  // backup
  ////////////////////////////////////////////////////////////////////////////
  protected File _getBackup(String prop)
  {
    File backup=null;
    String p=getProperty(prop);
    if (Utils.isEmpty(p)) p=getProperty(Settings.BACKUP_PATH);
    if (!Utils.isEmpty(p)) {
      backup=new File(p);
      backup.mkdirs();
      if (debug) System.out.println("using backup folder "+backup);
    }
    return backup;
  }

  public File getInfoBackup()
  { return _getBackup(Settings.INFO_BACKUP_PATH);
  }

  public File getRasterBackup()
  { return _getBackup(Settings.RASTER_BACKUP_PATH);
  }

  public File getRasterImageBackup()
  { return _getBackup(Settings.RASTERIMAGE_BACKUP_PATH);
  }

  public boolean backupFile(AbstractFile f, File backup)
  {
    if (backup!=null && f.isFile()) {
      File n=new File(backup,f.getName());
      if (debug) System.out.println("saving "+f);
      try {
        if (!MandelFolder.Util.renameTo(f.getFile(), n)) {
          MandelFolder.Util.delete(n);
          return MandelFolder.Util.renameTo(f.getFile(), n);
        }
      }
      catch (IOException io) {
        System.err.println("cannot save "+f+": "+io);
      }
      return true;
    }
    return false;
  }

  public boolean backupInfoFile(AbstractFile f)
  { return backupFile(f,getInfoBackup());
  }

  public boolean backupRasterFile(AbstractFile f)
  { return backupFile(f,getRasterBackup());
  }

  public boolean backupRasterImageFile(AbstractFile f)
  { return backupFile(f,getRasterImageBackup());
  }


  
  ///////////

  public File mapToRasterFile(AbstractFile f)
  { return MandUtils.mapFile(f,RASTER_SUFFIX,getRasterFolder(f));
  }

  public File mapToInfoFile(AbstractFile f)
  { return MandUtils.mapFile(f,INFO_SUFFIX,getInfoFolder(f));
  }

  public File mapToRasterImageFile(AbstractFile f)
  { return MandUtils.mapFile(f,RASTERIMAGE_SUFFIX,getRasterImageFolder(f));
  }

  public File mapToImageFile(AbstractFile f)
  { return MandUtils.mapFile(f,IMAGE_SUFFIX,getImageFolder(f));
  }

  protected void seenModified()
  {
  }

  ///////////////////////////////////////////////////////////////////////////
  // Auto
  ///////////////////////////////////////////////////////////////////////////

  private class AutoScanner extends MandelScannerProxy 
                            implements ContextMandelScanner {
    private boolean iscontext;

    public AutoScanner(MandelScanner s)
    {
      super(s);
      iscontext=s instanceof ContextMandelScanner;
    }

    @Override
    public void rescan(boolean verbose)
    {
      if (isAutoRescan())
        super.rescan(verbose);
    }

    public MandelImageDBContext getContext()
    {
      return context;
    }

    public Set<MandelName> getSubNames(MandelName n)
    {
      if (iscontext) {
        return ((ContextMandelScanner)getScanner()).getSubNames(n);
      }
      else {
        return MandelScannerUtils.getSubNames(n, null, getScanner());
      }
    }

    public Set<MandelName> getSubNames(MandelName n, Filter f)
    {
      if (iscontext) {
        return ((ContextMandelScanner)getScanner()).getSubNames(n,f);
      }
      else {
        return MandelScannerUtils.getSubNames(n, null, getScanner(), f);
      }
    }

    public boolean hasSubNames(MandelName n)
    {
      if (iscontext) {
        return ((ContextMandelScanner)getScanner()).hasSubNames(n);
      }
      else {
        return MandelScannerUtils.hasSubNames(n, null, getScanner());
      }
    }

    public boolean hasSubNames(MandelName n, Filter f)
    {
      if (iscontext) {
        return ((ContextMandelScanner)getScanner()).hasSubNames(n,f);
      }
      else {
        return MandelScannerUtils.hasSubNames(n, null, getScanner(),f);
      }
    }

    ////////////////////////////////////////////////////////////////////////

  }

  ///////////////////////////////////////////////////////////////////////////
  // main
  ///////////////////////////////////////////////////////////////////////////

  public static void main(String[] args)
  {
    try {
      Environment1 env=new Environment1((String[])null);
      //    System.out.println(s.getColormapNames());
      //    System.out.println(s.getColormapNames());
    }
    catch (IllegalConfigurationException ex) {
      System.out.println(""+ex);
    }
  }
}