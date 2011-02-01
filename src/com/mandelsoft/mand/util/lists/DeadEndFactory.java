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

package com.mandelsoft.mand.util.lists;

import com.mandelsoft.mand.MandelName;
import com.mandelsoft.mand.QualifiedMandelName;
import com.mandelsoft.mand.scan.MandelScanner;
import com.mandelsoft.mand.util.MandUtils;

/**
 *
 * @author Uwe Krueger
 */
public class DeadEndFactory extends SubAreaFactory {

  public DeadEndFactory(MandelScanner scanner, MandelName basename)
  {
    super(scanner,basename,"Dead Ends");
  }

  @Override
  protected boolean accept(QualifiedMandelName n)
  {
    if (super.accept(n) && !MandUtils.hasSubNames(n.getMandelName(), getScanner())) {
      return true;
    }
    return false;
  }

}