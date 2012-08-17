/* 
 * Copyright 2011 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.tests.minitutorials.v7a3;

import com.vaadin.terminal.WrappedRequest;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Root;

public class FlotJavaScriptRoot extends Root {

    @Override
    protected void init(WrappedRequest request) {
        final Flot flot = new Flot();
        flot.setHeight("300px");
        flot.setWidth("400px");

        flot.addSeries(1, 2, 4, 8, 16);
        addComponent(flot);

        addComponent(new Button("Highlight point", new Button.ClickListener() {
            @Override
            public void buttonClick(ClickEvent event) {
                flot.highlight(0, 3);
            }
        }));
    }

}