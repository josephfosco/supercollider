ScDocParser {
    var <>root;
    var tree;
    var stack;
    var current;
    var singleline;
    var level;
    var modalTag;
    var lastTagLine;
    var afterClosing;
    var isWS;
    var stripFirst;
    var proseDisplay;

    init {
        root = tree = List.new;
        stack = List.new;
        stack.add([tree,0,nil]);
        current = nil;
        singleline = false;
        level = 0;
        modalTag = nil;
        isWS = false;
        afterClosing = false;
        stripFirst = false;
        proseDisplay = \block;
//        doingInlineTag = false;
    }

//    *new {|filename|
//        ^super.newCopyArgs(filename).init;
//    }
//    *new {
//        ^super.new.init;
//    }
//    isTag {|word| ^"^(::[a-zA-Z]+|[a-zA-Z]+::)$".matchRegexp(word)}
//    isOpeningTag {|word| ^"^[a-zA-Z]+::$".matchRegexp(word)}
//    isClosingTag {|word| ^"^::[a-zA-Z]+$".matchRegexp(word)}
    leaveLevel {|n|
        var p;
        while({level>=n},{
            p = stack.pop;
            tree = p[0];
            level = p[1];
        });
    }

    popTree {
        var p = stack.pop;
        p !? {
            tree = p[0];
            level = p[1];
            p[2] !? {proseDisplay = p[2].display};
        };
    }

    pushTree {
        stack.add([tree,level,nil]);
    }

    setTopNode {|n|
        stack[stack.size-1][2] = n;
    }

    enterLevel {|n|
        this.leaveLevel(n);
        this.pushTree;
        level = n;
    }

    endCurrent {
        current !? {
            proseDisplay = current.display;
            current = nil;
        };
    }

    addTag {|tag, text="", children=false, display=\block|
        var node;
        this.endCurrent;
        tag = tag.asString.drop(-2).asSymbol;
        current = node = (tag:tag, display:display, text:text, children:if(children,{List.new},{nil}));
        tree.add(current);
        if(children, {tree = current.children}); //recurse into children list
        if(text.isNil) {this.endCurrent}; //we don't have any text field to add to for this tag, so start fresh..    
        ^node;
    }

    handleWord {|word,lineno,wordno|
        var tag = word.toLower.asSymbol;
        var t,x;
        var simpleTag = {
            singleline = true;
            this.addTag(tag);
            stripFirst = true;
        };
        var noNameSection = {
            singleline = true; //this doesn't actually matter here since we don't have a text field?
            this.enterLevel(1);
            this.setTopNode(this.addTag(tag,nil,true));
        };
        var namedSection = {|lev|
            {
                singleline = true;
                this.enterLevel(lev);
                this.setTopNode(this.addTag(tag,"",true));
                stripFirst = true;
            }
        };
        var modalRangeTag = {
            singleline = false;
            this.addTag(tag);
            lastTagLine = lineno;
            modalTag = '::';
        };
        var listEnter = {
            singleline = false; //this doesn't actually matter here since we don't have a text field?
            this.pushTree;
            this.setTopNode(this.addTag(tag,nil,true));
            lastTagLine = lineno;
        };

        // modal tags ignore all other tags until their closing tag occurs.
        // here we check if we are in a modal tag (code, emphasis, link) and then
        // if we got the closing tag.
        if(modalTag.notNil, {
            //only allow modal block tags to be closed with the closing tag as the first word on a line
            if((tag==modalTag) and: ((wordno==0) or: (lastTagLine==lineno)),{
                current !? {
                    current.display = if(lastTagLine==lineno,\inline,\block);
                };
                this.endCurrent;
                modalTag = nil;
                afterClosing = true;
            },{
                if(("[^ ]+[^ \\]::".matchRegexp(word)) and: (lastTagLine==lineno), { //split unhandled tag-like word
                    this.addText(word.drop(-2));
                    this.handleWord("::",lineno,wordno+1);
                },{
                    this.addText(word.replace("\\::","::"));
                });
            });
        },{
            switch(tag,
                'description::',        noNameSection, //level 1
                'classmethods::',       noNameSection,
                'instancemethods::',    noNameSection,                
                'examples::',           noNameSection,
                'section::',            namedSection.(1),
                'subsection::',         namedSection.(2),
                'method::',             namedSection.(3),
                'argument::',           namedSection.(4),
                'returns::',            {
                    singleline = true; //this doesn't actually matter here since we don't have a text field?
                    this.enterLevel(4);
                    this.setTopNode(this.addTag(tag,nil,true));
                },
                'discussion::',            {
                    singleline = true; //this doesn't actually matter here since we don't have a text field?
                    this.enterLevel(4);
                    this.setTopNode(this.addTag(tag,nil,true));
                },
                'class::',              simpleTag,
                'title::',              simpleTag,
                'summary::',            simpleTag,
                'related::',            simpleTag,
//                'headerimage::',        simpleTag,
                'categories::',         simpleTag,
//                'note::',               simpleTag,
//                'warning::',            simpleTag,
                'private::',            simpleTag,
                
                'code::',               modalRangeTag,
                'formula::',            modalRangeTag,
                'emphasis::',           modalRangeTag,
                'strong::',             modalRangeTag,
                'link::',               modalRangeTag,
                'anchor::',             modalRangeTag,
                'image::',              modalRangeTag,
                'soft::',               modalRangeTag,

                'note::',               { listEnter.(); proseDisplay=\inline },
                'warning::',            { listEnter.(); proseDisplay=\inline },

                'list::',               listEnter,
                'tree::',               listEnter,
                'numberedlist::',       listEnter,
                'definitionlist::',     listEnter,
                'table::',              listEnter,
                'footnote::',           {
                    singleline = false;
                    current !? { //strip trailing whitespace from previous text..
                        t=current.text;
                        x=t.size-1;
                        while({(t[x]==$\n) or: (t[x]==$\ )},{x=x-1});
                        current.text = t.copyRange(0,x);
                    };
                    this.pushTree;
                    this.setTopNode(this.addTag(tag,nil,true,\inline));
                    lastTagLine = lineno;
                    proseDisplay = \inline;
                },
                '##', {
                    singleline = false;
                    this.addTag('##::',nil,false,\inline); //make it look like an ordinary tag since we drop the :: in the output tree
                },
                '||', {
                    singleline = false;
                    this.addTag('||::',nil,false,\inline);
                },
                '::', { //ends tables and lists
                    this.endCurrent;
                    this.popTree;
                },
                '\\::', {
                    this.addText("::");
                },

                { //default case
                    if("^[a-zA-Z]+://.+".matchRegexp(word),{ //auto link URIs
                        this.addTag('link::',word++" ",false,\inline);
                        this.endCurrent;
                    },{
                        if(("[^ ]+[^ \\]::".matchRegexp(word)) and: (lastTagLine==lineno), { //split unhandled tag-like word
                            this.addText(word.drop(-2));
                            this.handleWord("::",lineno,wordno+1);
                        },{
                            this.addText(word); //plain text, add the word.
                        });
                    });
                }
            );
        });
    }

    addText {|word|
        if(stripFirst, {
            stripFirst = false;
            word = word.stripWhiteSpace;
        });
        if(current.notNil, { // add to current element text
            current.text = current.text ++ word
        },{ // no current element, so add to new 'prose' element
            if((isWS.not) or: (afterClosing), { //don't start a new prose element with whitespace
                afterClosing = false;
                singleline = false;
                this.addTag('prose::', word, false, proseDisplay);
            });
        });
    }

    endLine {
        if(singleline,{this.endCurrent});
        // pass through newlines for vari-line tags.
        current !? {current.text = current.text ++ "\n"};
    }

    parse {|string|
        var lines = string.split($\n); //split lines
//        var lines = string.findRegexp("[^\n]+").flop[1]; //doesn't work for empty lines
        
        var w, split, split2, word;
        this.init;
        lines.do {|line,l|
            split = line.findRegexp("[a-zA-Z]+::[^ \n\t]+::|[a-zA-Z]*::|[ \n\t]+|[^ \n\t]+"); //split words and tags and ws
            w = 0;
            split.do {|e|
                word = e[1];
                split2 = word.findRegexp("([a-zA-Z]+::)([^ \n\t]+)(::)")[1..]; //split stuff like::this::...
                if(split2.isEmpty,{
                    isWS = "^[ \n\t]+$".matchRegexp(word);
                    this.handleWord(word,l,w);
                    if(isWS.not,{w=w+1});
                },{
                    split2.do {|e2|
                        isWS = "^[ \n\t]+$".matchRegexp(e2[1]);
                        this.handleWord(e2[1],l,w);
                        w=w+1;
                    };
                });
            };
            if(modalTag.isNil and: split.isEmpty, { this.endCurrent; proseDisplay=\block; }); //force a new prose on double blank lines
            this.endLine;
        };
    }

    parseFile {|filename|
        var file = File.open(filename,"r");
        ScDoc.postProgress("Parsing "++filename);
        this.parse(file.readAllString);
        file.close;
    }

    generateUndocumentedMethods {|class,node,title|
        var syms, name, mets, l = List.new;
        var docmets = IdentitySet.new;
        
        var addMet = {|n|
            n.text.findRegexp("[^ ,]+").flop[1].do {|m|
                docmets.add(m.asSymbol.asGetter);
            };
        };

        var do_children = {|children|
            children !? {
                children.do {|n|
                    switch(n.tag,
                        \method, { addMet.(n) },
                        \private, { addMet.(n) },
                        \subsection, { do_children.(n.children) }
                    );
                };
            };
        };

        if(class.isNil, {^nil});
        
        do_children.(node.children);
        
        (mets = class.methods) !? {
            //ignore these methods by default. Note that they can still be explicitly documented.
            docmets = docmets | IdentitySet[\categories, \init, \checkInputs, \new1, \argNamesInputsOffset];
            syms = mets.collectAs({|m|m.name.asGetter},IdentitySet);
            syms.do {|name|
                if(docmets.includes(name).not) {
                    l.add((tag:\method, text:name.asString));
                }
            };
        };

        ^ if(l.notEmpty,
        {
            (tag:\subsection,
            text:title,
            children:l)
        },
            nil
        );
    }

    dumpSubTree {|t,i="",lev=1|
        t.do {|e|
            "".postln;
            (i++"TAG:"+e.tag+"( level"+lev+e.display+")").postln;
            e.text !? {
                (i++"TEXT: \""++e.text++"\"").postln;
            };
            e.children !? {
                (i++"CHILDREN:").postln;
                this.dumpSubTree(e.children,i++"    ",lev+1);
            };
        }
    }

    dump {
        this.dumpSubTree(root);
        ^nil;
    }

    findNode {|tag,rootNode=nil|
        var res = nil;
        (rootNode ?? { root }).do {|n|
            if(n.tag == tag.asSymbol, { res = n});
        };
        if(res.notNil, {
            ^res;
        }, {
            ^(tag:nil, text:"", children:[]);
        });
    }

    dumpClassTree {|node,c|
        var n;
        if(c.name.asString.find("Meta_")==0, {^nil});
        node.children.add((tag:'##'));
        node.children.add((tag:'link', text:"Classes/"++c.name.asString));
        
        c.subclasses !? {
            n = (tag:'tree', children:List.new);
            node.children.add(n);
            c.subclasses.copy.sort {|a,b| a.name < b.name}.do {|x|
                this.dumpClassTree(n,x);
            };
        };
    }

    overviewClassTree {
        var r = List.new;
        var n = (tag:'tree', children:List.new);
        r.add((tag:'title', text:"Class Tree"));
        r.add((tag:'summary', text:"All classes by inheritance tree"));
        r.add((tag:'related', text:"Overviews/Classes, Overviews/Categories, Overviews/Methods"));
//        r.add((tag:'categories', text:"Classes"));
        r.add(n);
        this.dumpClassTree(n,Object);
        root = r;
    }

    makeCategoryTree {|catMap,node,filter=nil,toc=false|
        var a, p, e, n, l, m, kinds, folder, v, dumpCats, sorted;
        var tree = Dictionary.new;

        catMap.pairsDo {|cat,files|
            p=tree;
            l=cat.split($>);
            if(filter.isNil or: {filter.matchRegexp(l.first)}, {
                l.do {|c|
                    if(p[c].isNil,{
                        p[c]=Dictionary.new;
                        p[c][\subcats] = Dictionary.new;
                        p[c][\entries] = List.new;
                    });
                    e=p[c];
                    p=p[c][\subcats];
                };
                a=e[\entries];
                files.do {|f| a.add(f)};
            });
        };


        dumpCats = {|x,l,y|
            var ents = x[\entries];
            var subs = x[\subcats];
            var c, z;

            if(ents.notEmpty, {
                ents.sort {|a,b| a.path.basename < b.path.basename}.do {|doc|
                    folder = doc.path.dirname;
                    folder = if(folder==".", {""}, {" ["++folder++"]"});
                    l.add((tag:'##'));
                    l.add((tag:'link', text:doc.path++"##"++doc.title));
                    l.add((tag:'prose', text:" - "++doc.summary));
                    l.add((tag:'soft', text:folder));
                    switch(doc.installed,
                        \extension, { l.add((tag:'soft', text:" (+)")) },
                        \missing, { l.add((tag:'strong', text:" (not installed)")) }
                    );
/*                    if(doc.path.dirname=="Classes") {
                        c = doc.path.basename.asSymbol.asClass;
                        if(c.notNil) {
                            if(c.filenameSymbol.asString.beginsWith(thisProcess.platform.classLibraryDir).not) {
                                l.add((tag:'soft', text:" (+)"));
                            };
                        } {
                            l.add((tag:'strong', text:" (not installed)"));
                        };
                    };
*/
                };
            });

            subs.keys.asList.sort {|a,b| a<b}.do {|k|
                z = ScDocRenderer.simplifyName(y++">"++k);
                l.add((tag:'##'));
                l.add((tag:\anchor, text:z));
                l.add((tag:\strong, text:k));
                l.add((tag:\tree, children:m=List.new));
                dumpCats.value(subs[k],m,z);
            };    
        };

        sorted = tree.keys.asList.sort {|a,b| a<b};

        if(toc) {
            node.add((tag:'prose', text:"Jump to: ", display:\block));
            sorted.do {|k,i|
                if(i!=0, {node.add((tag:'prose', text:", ", display:\inline))});
                node.add((tag:'link', text:"#"++ScDocRenderer.simplifyName(k)++"#"++k));
//                node.add((tag:'prose', text:" ", display:\inline));
            };
        };
        
        sorted.do {|k|
            node.add((tag:\section, text:k, children:m=List.new));
            m.add((tag:\tree, children:l=List.new));
            dumpCats.(tree[k],l,k);
        };
    }

    overviewCategories {|catMap|
        var r = List.new;
//        var a, p, e, n, l, m, kinds, folder, v, tree, dumpCats;
        r.add((tag:'title', text:"Document Categories"));
        r.add((tag:'summary', text:"All documents by categories"));
        r.add((tag:'related', text:"Overviews/Documents, Browse, Search"));
        
        this.makeCategoryTree(catMap,r,toc:false);
        
        // kind - category
/*        kinds = Dictionary.new;
        catMap.pairsDo {|k,v|
            v.do {|file|
                folder = file.path.dirname;
                if(folder!=".", {
                    if(kinds[folder].isNil, { kinds[folder] = Dictionary.new });
                    if(kinds[folder][k].isNil, { kinds[folder][k] = List.new });
                    kinds[folder][k].add(file);
                });
            };
        };
        kinds.keys.asList.sort {|a,b| a<b}.do {|k|
            v = kinds[k];
            r.add((tag:'section', text:k, children:n=List.new));
            v.keys.asList.sort {|a,b| a<b}.do {|cat|
                n.add((tag:'subsection', text:cat, children:m=List.new));
                m.add((tag:'list', children:l=List.new));
                v[cat].do {|doc|
                    l.add((tag:'##'));
                    l.add((tag:'link', text:doc.path));
                    l.add((tag:'prose', text:" - "++doc.summary));
                };
            };
        };*/
        root = r;
    }

    overviewAllClasses {|docMap|
        var name, doc, link, n, r = List.new, cap, old_cap=nil, sortedKeys;
        r.add((tag:'title', text:"Classes"));
        r.add((tag:'summary', text:"Alphabetical index of all classes"));
        r.add((tag:'related', text:"Overviews/ClassTree, Overviews/Categories, Overviews/Methods"));

        sortedKeys = Class.allClasses.reject {|c| c.name.asString.find("Meta_")==0};

        old_cap = nil;
        r.add((tag:'prose', text:"Jump to: ", display:\block));
        sortedKeys.do {|c|
            name = c.name.asString;
            cap = name.first.toUpper;
            if(cap!=old_cap, {
                r.add((tag:'link', text:"#"++cap.asString));
                r.add((tag:'prose', text:" ", display:\inline));
                old_cap = cap;
            });
        };

        old_cap = nil;
        sortedKeys.do {|c|
            name = c.name.asString;
            link = "Classes" +/+ name;
            doc = docMap[link];
            cap = name.first.toUpper;
            if(cap!=old_cap, {
                r.add((tag:'section', text:cap.asString, children:n=List.new));
                n.add((tag:'list', children:n=List.new));
                old_cap = cap;
            });
            n.add((tag:'##'));
            n.add((tag:'link', text: link));
            n.add((tag:'prose', text: " - "++ if(doc.notNil, {doc.summary}, {""})));
            switch(doc.installed,
                \extension, { n.add((tag:'soft', text:" (+)")) },
                \missing, { n.add((tag:'strong', text:" (not installed)")) }
            );
        };
        root = r;
    }

    overviewAllMethods {|docMap|
        var name, n, r = List.new, cap, old_cap, t, m, ext, sortedKeys;
        r.add((tag:'title', text:"Methods"));
        r.add((tag:'summary', text:"Alphabetical index of all methods"));
        r.add((tag:'related', text:"Overviews/ClassTree, Overviews/Classes"));
        
        r.add((tag:'prose', text:"This is an alphabetical list of all implemented methods, including private and undocumented methods.", display:\block));

        t = IdentityDictionary.new;

        Class.allClasses.do {|c|
            name = c.name.asString;
            c.methods.do {|x|
                if(t[x.name]==nil, {t[x.name] = List.new});

                t[x.name].add([name,x.isExtensionOf(c)]);
            };
        };

        sortedKeys = t.keys.asList.sort {|a,b| a<b};

        old_cap = nil;
        r.add((tag:'prose', text:"Jump to: ", display:\block));
        sortedKeys.do {|k|
            name = k.asString;
            cap = name.first.toLower;
            if(cap!=old_cap, {
                r.add((tag:'link', text:"#"++cap.asString));
                r.add((tag:'prose', text:" ", display:\inline));
                old_cap = cap;
            });
        };

        old_cap = nil;
        sortedKeys.do {|k|
            name = k.asString;
                cap = name.first.toLower;
                if(cap!=old_cap, {
                    r.add((tag:'section', text:cap.asString, children:n=List.new));
                    n.add((tag:'definitionlist', children:m=List.new));
                    old_cap = cap;
                });

            m.add((tag:'##'));
            m.add((tag:'anchor', text:name));
            m.add((tag:'prose', text:name));
            m.add((tag:'||'));
            if(name.last==$_, {name=name.drop(-1)});
            t[k].do {|c,i|
                n = c[0];
                if(n.find("Meta_")==0, {n = n.drop(5)});
                if(i!=0, {m.add((tag:'prose', text:", ", display:\inline))});
                if(c[1], {m.add((tag:'prose', text:"+", display:\inline))});
                m.add((tag:'link', text: "Classes" +/+ n ++ "#" ++ ScDocRenderer.simplifyName(name)));
            };
        };

        root = r;
        ^t;
    }

    overviewAllDocuments {|docMap|
        var kind, name, doc, link, n, r = List.new, cap, old_cap, sortedKeys;
        r.add((tag:'title', text:"Documents"));
        r.add((tag:'summary', text:"Alphabetical index of all documents"));
        r.add((tag:'related', text:"Overviews/Categories"));

        sortedKeys = docMap.keys.asList.sort {|a,b| a.split($/).last < b.split($/).last};

        old_cap = nil;
        r.add((tag:'prose', text:"Jump to: ", display:\block));
        sortedKeys.do {|link|
            name = link.split($/).last;
            cap = name.first.toUpper;
            if(cap!=old_cap, {
                r.add((tag:'link', text:"#"++cap.asString));
                r.add((tag:'prose', text:" ", display:\inline));
                old_cap = cap;
            });
        };

        old_cap = nil;
        sortedKeys.do {|link|
            doc = docMap[link];
            name = link.split($/).last;
            kind = link.dirname;
            kind = if(kind==".", {""}, {" ["++kind++"]"});
            cap = name.first.toUpper;
            if(cap!=old_cap, {
                r.add((tag:'section', text:cap.asString, children:n=List.new));
                n.add((tag:'list', children:n=List.new));
                old_cap = cap;
            });
            n.add((tag:'##'));
            n.add((tag:'link', text: link++"##"++doc.title));
//            n.add((tag:'||'));
            n.add((tag:'prose', text: " - "++if(doc.notNil, {doc.summary}, {""})));
//            n.add((tag:'||'));
            n.add((tag:'soft', text: kind));
        };
        root = r;
    }

/*    overviewServer {|catMap|
        var r = List.new;
        r.add((tag:'title', text:"Server stuff"));
        r.add((tag:'summary', text:"Overview of server related stuff"));

        this.makeCategoryTree(catMap,r,"^Server$"); 
        this.makeCategoryTree(catMap,r,"^UGens$");
        root = r;
    }*/
}

ScDocRenderer {
    var <>parser;

    var currentClass;
    var collectedArgs;
//    var retValue;
    var dirLevel;
    var baseDir;
    var footNotes;

    *new {|p=nil|
        ^super.newCopyArgs(p);//.init;
    }

//    init {
//    }

    *simplifyName {|txt|
        ^txt.toLower.tr($\ ,$_);
    }

    escapeSpecialChars {|str|
//        ^str.replace("\"","&quot;").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        ^str.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    renderFootNotes {|file|
        if (footNotes.notEmpty) {
            file.write("<div class='footnotes'>\n");
            footNotes.do {|n,i|
                file.write("<a name='footnote_"++(i+1)++"'/><div class='footnote'>");
                file.write("[<a href='#footnote_org_"++(i+1)++"'>"++(i+1)++"</a>] - ");
                n.children.do(this.renderHTMLSubTree(file,_));
                file.write("</div>");
            };
            file.write("</div>");
        };
    }

    renderHTMLSubTree {|file,node,parentTag=false|
        var c, f, m, n, mname, args, split, mstat, sym, css;

        var do_children = {|p=false|
            node.children !? {
                node.children.do {|e| this.renderHTMLSubTree(file,e,if(p,{node.tag},{parentTag})) };
            };
        };

        switch(node.tag,
            'prose', {
                if(node.display == \block, {
                    file.write("<p>"++this.escapeSpecialChars(node.text));
                }, {
                    file.write(this.escapeSpecialChars(node.text));                
                });
            },
            'section', {
                file.write("<a name='"++ScDocRenderer.simplifyName(node.text)++"'><h2>"++this.escapeSpecialChars(node.text)++"</h2></a>\n");
                do_children.();
            },
            'subsection', {
                file.write("<a name='"++ScDocRenderer.simplifyName(node.text)++"'><h3>"++this.escapeSpecialChars(node.text)++"</h3></a>\n");
                do_children.();
            },
            'classmethods', {
                if(node.children.select{|n|n.tag!=\private}.notEmpty) {
                    file.write("<a name='classmethods'><h2>Class Methods</h2></a>\n<div id='classmethods'>");
                    do_children.(true);
                    file.write("</div>");
                } {
                    do_children.(true);
                };
            },
            'instancemethods', {
                if(node.children.select{|n|n.tag!=\private}.notEmpty) {
                    file.write("<a name='instancemethods'><h2>Instance Methods</h2></a>\n<div id='instancemethods'>");
                    do_children.(true);
                    file.write("</div>");
                } {
                    do_children.(true);
                };
            },
            'private', {
            },
            'method', {
                //for multiple methods with same signature and similar function:
                //ar kr (x = 42, y = 123)
                f = node.text.findRegexp(" *([^(]+) *(\\(.*\\))?");
                args = "";
//FIXME: handle overridden argumentnames/defaults?
//perhaps we should check argument names at least? and only use overriding for "hidden" default values?
//also, perhaps better to read the default values from the argument tags?
//ignore markup-provided arguments for now..
                c = if(parentTag==\instancemethods,{currentClass},{currentClass.class});
                css = if(parentTag==\instancemethods,{"imethodname"},{"cmethodname"});
                split = f[1][1].findRegexp("[^ ,]+");
                split.do {|r|
                    mstat = 0;
                    mname = r[1];
                    sym = mname.asSymbol;
                    //check for normal method or getter
                    m = c.findRespondingMethodFor(sym.asGetter);
                    m !? {
                        mstat = mstat | 1;
//                        mets.add(sym.asGetter);
                        args = ScDoc.makeArgString(m);
                    };
                    //check for setter
                    c.findRespondingMethodFor(sym.asSetter) !? {
                        mstat = mstat | 2;
//                        mets.add(sym.asSetter);
                    };

                    switch (mstat,
                        // getter only
                        1, { file.write("<a name='"++mname++"'><h3 class='"++css++"'>"++this.escapeSpecialChars(mname)++" "++args++"</h3></a>\n"); },
                        // setter only
                        2, { file.write("<a name='"++mname++"'><h3 class='"++css++"'>"++this.escapeSpecialChars(mname)++" = value</h3></a>\n"); },
                        // getter and setter
                        3, { file.write("<a name='"++mname++"'><h3 class='"++css++"'>"++this.escapeSpecialChars(mname)++" [= value]</h3></a>\n"); },
                        // method not found
                        0, { file.write("<a name='"++mname++"'><h3 class='"++css++"'>"++this.escapeSpecialChars(mname)++": METHOD NOT FOUND!</h3></a>\n"); }
                    );
                    //Note: this only checks if the getter is an extension if there are both getter and setter..
                    if(m.notNil) {
                        if(m.isExtensionOf(c)) {
                            file.write("<div class='extmethod'>From extension in <a href='" ++ m.filenameSymbol ++ "'>" ++ m.filenameSymbol ++ "</a></div>\n");
                        } {
                            if(m.ownerClass != c) {
                                n = m.ownerClass.name.asString.replace("Meta_","");
                                file.write("<div class='supmethod'>From superclass: <a href='" ++ baseDir +/+ "Classes" +/+ n ++ ".html'>" ++ n ++ "</a></div>\n");
                            }
                        };
                    };
                };

                file.write("<div class='method'>");

                collectedArgs = [];
                do_children.();
                
                if(collectedArgs.notEmpty) {
                    file.write("<h4>Parameters:</h4>\n");
                    file.write("<table class='arguments'>\n");
                    collectedArgs.do {|a|
                        file.write("<tr><td class='argumentname'>"+a.text+"<td class='argumentdesc'>");
                        a.children.do {|e| this.renderHTMLSubTree(file,e,false) };
                    };
                    file.write("</table>");
                };

//                file.write("<h4>Parameters:</h4>\n");
/*                file.write("<table class='arguments'>\n");
                node.children.do {|a|
                    if(a.tag == \argument, {
                            file.write("<tr><td class='argumentname'>"+a.text+"<td>");
                            a.children.do {|e| this.renderHTMLSubTree(file,e,false) };
                    });
                };*/
                
                n = this.parser.findNode(\returns, node.children);
                if(n.tag.notNil) {
//                    file.write("<tr><td class='returnvalue'>returns:<td>");                    
                    file.write("<h4>Returns:</h4>\n<div class='returnvalue'>");
                    n.children.do {|e| this.renderHTMLSubTree(file,e,false) };
                    file.write("</div>");
                };

/*                node.children.do {|a|
                    if(a.tag == \returns, {
                        file.write("<tr><td class='returnvalue'>returns:<td>");
                        a.children.do {|e| this.renderHTMLSubTree(file,e,false) };
                    });
                };
*/
//                file.write("</table>");
                n = this.parser.findNode(\discussion, node.children);
                if(n.tag.notNil) {
                    file.write("<h4>Discussion:</h4>\n");
                    n.children.do {|e| this.renderHTMLSubTree(file,e,false) };
                };

/*                collectedArgs = [];
                retValue = nil;
                do_children.();
                file.write("<table class='arguments'>\n");
                collectedArgs.do {|a|
                    file.write("<tr><td class='argumentname'>"+a.text+"<td>");
                    a.children.do {|e| this.renderHTMLSubTree(file,e,a.tag) };
                };
                if(retValue.notNil) {
                    file.write("<tr><td class='returnvalue'>returns:<td>");
                    retValue.children.do {|e| this.renderHTMLSubTree(file,e,false) };
                };
                file.write("</table>");
*/
                file.write("</div>");
            },
            'argument', {
                collectedArgs = collectedArgs.add(node);
            },
            'returns', {
//                retValue = node;
            },
            'description', {
                if(node.children.notEmpty) {
                    file.write("<a name='description'><h2>Description</h2></a>\n<div id='description'>");
                    do_children.();
                    file.write("</div>");
                };
            },
            'examples', {
                file.write("<a name='examples'><h2>Examples</h2></a>\n<div id='examples'>");
                do_children.();
                file.write("</div>");
            },
            'note', {
                file.write("<div class='note'><span class='notelabel'>NOTE:</span> ");
                do_children.();
                file.write("</div>");
            },
            'warning', {
                file.write("<div class='warning'><span class='warninglabel'>WARNING:</span> ");
                do_children.();
                file.write("</div>");
            },
            'emphasis', {
                file.write("<em>"++this.escapeSpecialChars(node.text)++"</em>");
            },
            'strong', {
                file.write("<strong>"++this.escapeSpecialChars(node.text)++"</strong>");
            },
            'soft', {
                file.write("<span class='soft'>"++this.escapeSpecialChars(node.text)++"</span>");
            },
            'link', {
                if("^[a-zA-Z]+://.+".matchRegexp(node.text) or: (node.text.first==$/),{
                    file.write("<a href=\""++node.text++"\">"++this.escapeSpecialChars(node.text)++"</a>");
                },{
                    f = node.text.split($#);
                    m = if(f[1].size>0, {"#"++f[1]}, {""});
                    n = f[2] ?? { f[0].split($/).last };
                    c = if(f[0].size>0, {baseDir +/+ f[0]++".html"},{n=f[2]??f[1];""});
                    file.write("<a href=\""++c++m++"\">"++this.escapeSpecialChars(n)++"</a>");
                });
            },
            'anchor', {
                file.write("<a name='"++node.text++"'</a>");
            },
            'code', {
                if(node.display == \block, {
                    file.write("<pre class='code'>"++this.escapeSpecialChars(node.text)++"</pre>\n");
                }, {
                    file.write("<code class='code'>"++this.escapeSpecialChars(node.text)++"</code>\n");
                });
            },
            'formula', {
                if(node.display == \block, {
                    file.write("<pre class='formula'>"++this.escapeSpecialChars(node.text)++"</pre>\n");
                }, {
                    file.write("<code class='formula'>"++this.escapeSpecialChars(node.text)++"</code>\n");
                });
            },
            'image', {
//                if(node.display == \block, {
                f = node.text.split($#);
                    file.write("<div class='image'><img src='"++f[0]++"'/>");
                    f[1] !? { file.write("<br><b>"++f[1]++"</b>") };
                    file.write("</div>\n");
//                }, {
//                    file.write("<span class='image'><img src='"++node.text++"'/></span>\n");
//                });
            },
            'list', {
                file.write("<ul>\n");
                do_children.(true);
                file.write("</ul>\n");
            },
            'tree', {
                file.write("<ul class='tree'>\n");
                do_children.(true);
                file.write("</ul>\n");
            },
            'definitionlist', {
                file.write("<dl>\n");
                do_children.(true);
                file.write("</dl>\n");
            },
            'numberedlist', {
                file.write("<ol>\n");
                do_children.(true);
                file.write("</ol>\n");
            },
            'table', {
                file.write("<table>\n");
                do_children.(true);
                file.write("</table>\n");
            },
            'footnote', {
                footNotes.add(node);
                file.write("<a class='footnote' name='footnote_org_"++footNotes.size++"' href='#footnote_"++footNotes.size++"'><sup>"++footNotes.size++"</sup></a> ");
            },
            '##', {
                switch(parentTag,
                    'list',             { file.write("<li>") },
                    'tree',             { file.write("<li>") },
                    'numberedlist',     { file.write("<li>") },
                    'definitionlist',   { file.write("<dt>") },
                    'table',            { file.write("<tr><td>") }
                );
            },
            '||', {
                switch(parentTag,
                    'definitionlist',   { file.write("<dd>") },
                    'table',            { file.write("<td>") }
                );
            },
            //these are handled explicitly
            'title', { },
            'summary', { },
            'class', { },
            'related', { },
            'categories', { },
            'headerimage', { },
            
            'root', {
                do_children.();
            },

            { //unhandled tag
//                file.write("(TAG:"++node.tag++")");
                node.text !? {file.write(this.escapeSpecialChars(node.text))};
            }
        );
    }

    renderTOC {|f|
        var do_children = {|children|
            children !? {
                f.write("<ul class='toc'>");
                children.do {|n|
                    switch(n.tag,
                        \description, {
                            f.write("<li class='toc1'><a href='#description'>Description</a></li>\n");
                            do_children.(n.children);
                        },
                        \examples, {
                            f.write("<li class='toc1'><a href='#examples'>Examples</a></li>\n");
                            do_children.(n.children);
                        },
                        \classmethods, {
                            f.write("<li class='toc1'><a href='#classmethods'>Class methods</a></li>\n");
                            do_children.(n.children);
                        },
                        \instancemethods, {
                            f.write("<li class='toc1'><a href='#instancemethods'>Instance methods</a></li>\n");
                            do_children.(n.children);
                        },
                        \method, {
                            f.write("<li class='toc3'>");
                            f.write(n.text.findRegexp("[^ ,]+").flop[1].collect {|m|
                                "<a href='#"++m++"'>"++m++"</a>";
                            }.join(", "));
                            f.write("</li>\n");
                        },
                        \section, {
                            f.write("<li class='toc1'><a href='#"++ScDocRenderer.simplifyName(n.text)++"'>"++n.text++"</a></li>\n");
                            do_children.(n.children);
                        },
                        \subsection, {
                            f.write("<li class='toc2'><a href='#"++ScDocRenderer.simplifyName(n.text)++"'>"++n.text++"</a></li>\n");
                            do_children.(n.children);
                        }
                    );
                };
                f.write("</ul>");
            }
        };
        f.write("<div id='toctitle'>Table of contents <a id='toc_toggle' href='#' onclick='showTOC(this);return false'></a></div>");
        f.write("<div id='toc'>\n");
        do_children.(parser.root);
        f.write("</div>");
    }

    renderHTMLHeader {|f,name,type,folder,toc=true|
        var x, cats, m, z;
        f.write("<html><head><title>"++name++"</title><link rel='stylesheet' href='"++baseDir++"/scdoc.css' type='text/css' />");
        f.write("<meta http-equiv='Content-Type' content='text/html; charset=UTF-8' />");
        f.write("<script src='" ++ baseDir ++ "/scdoc.js' type='text/javascript'></script>");
        f.write("</head><body>");
        
        f.write(
            "<table class='headMenu'><tr>"
            "<td><a href='" ++ baseDir +/+ "Help.html'>Home</a>"
            "<td><a href='" ++ baseDir +/+ "Browse.html'>Browse</a>"
            "<td><a href='" ++ baseDir +/+ "Overviews/Categories.html'>Categories</a>"
            "<td><a href='" ++ baseDir +/+ "Overviews/Documents.html'>Document index</a>"
            "<td><a href='" ++ baseDir +/+ "Overviews/Classes.html'>Class index</a>"
            "<td><a href='" ++ baseDir +/+ "Overviews/Methods.html'>Method index</a>"
            "<td><a href='" ++ baseDir +/+ "Search.html'>Search</a>"
            "</table>"
        );

//        cats = ScDoc.splitList(parser.findNode(\categories).text);
//        cats = if(cats.notNil, {cats.join(", ")}, {""});
        if(folder==".",{folder=""});
        f.write("<div class='header'>");
//        f.write("<div id='label'><a href='"++baseDir+/+"Help.html"++"'>SuperCollider</a> "++folder.asString.toUpper++"</div>");
        f.write("<div id='label'>SuperCollider "++folder.asString.toUpper++"</div>");
        x = parser.findNode(\categories);
        if(x.text.notEmpty, {
            f.write("<div id='categories'>");
//            f.write("Categories: ");
            f.write(ScDoc.splitList(x.text).collect {|r|
//                "<a href='"++baseDir +/+ "Overviews/Categories.html#"++ScDocRenderer.simplifyName(r).split($>).first++"'>"++r++"</a>"
                "<a href='"++baseDir +/+ "Overviews/Categories.html#"++ScDocRenderer.simplifyName(r)++"'>"++r++"</a>"
            }.join(", "));
            f.write("</div>");
        });    

        f.write("<h1>"++name);
//        x = parser.findNode(\headerimage);
//        if(x.text.notEmpty, { f.write("<span class='headerimage'><img src='"++x.text++"'/></span>")});
        if((folder=="") and: {name=="Help"}, {
            f.write("<span class='headerimage'><img src='"++baseDir++"/images/SC_icon.png'/></span>");
        });
        f.write("</h1>");
        x = parser.findNode(\summary);
        f.write("<div id='summary'>"++this.escapeSpecialChars(x.text)++"</div>");
        f.write("</div>");

        f.write("<div class='subheader'>\n");

        if(type==\class) {
            if(currentClass.notNil) {
                m = currentClass.filenameSymbol.asString;
                f.write("<div id='filename'>Location: "++m.dirname++"/<a href='file://"++m++"'>"++m.basename++"</a></div>");
                if(currentClass != Object) {
                    f.write("<div class='inheritance'>");
                    f.write("Inherits from: ");
                    f.write(currentClass.superclasses.collect {|c|
                        "<a href=\"../Classes/"++c.name++".html\">"++c.name++"</a>"
                    }.join(" : "));
                    f.write("</div>");
                };
                if(currentClass.subclasses.notNil) {
                    f.write("<div class='inheritance'>");
                    f.write("Subclasses: ");
                    f.write(currentClass.subclasses.collect {|c|
                        "<a href=\"../Classes/"++c.name++".html\">"++c.name++"</a>"
                    }.join(", "));
                    f.write("</div>");
                };
            } {
                f.write("<div id='filename'>Location: <b>NOT INSTALLED!</b></div>");
            };
        };
        
        x = parser.findNode(\related);
        if(x.text.notEmpty, {
            f.write("<div id='related'>");
            f.write("See also: ");
            f.write(ScDoc.splitList(x.text).collect {|r|
                z = r.split($#);
                m = if(z[1].size>0, {"#"++z[1]}, {""});
                "<a href=\""++baseDir +/+ z[0]++".html"++m++"\">"++r.split($/).last++"</a>"
            }.join(", "));
            f.write("</div>");
        });

        if((type==\class) and: {currentClass.notNil}, { // FIXME: Remove this when conversion to new help system is done!
            f.write("[ <a href='"++currentClass.helpFilePath++"'>old help</a> ]");
        });

        f.write("</div>");
        
        if(toc, {this.renderTOC(f)});
    }

    addUndocumentedMethods {|class,tag|
        var node = parser.findNode(tag);
        var mets = parser.generateUndocumentedMethods(class, node,
            "Undocumented "++if(tag==\classmethods,"class methods","instance methods"));
        mets !? {
            if(node.tag.isNil, { //no subtree, create one
                parser.root.add(node = (tag:tag, children:List.new));
            });
            node.children.add(mets);
        };
        ^node;
    }

    renderHTML {|filename, folder=".", toc=true|
        var f,x,name,mets,inode,cnode;
        
        ScDoc.postProgress("Rendering "++filename);

        ("mkdir -p"+filename.dirname.escapeChar($ )).systemCmd;

        f = File.open(filename, "w");

        //folder is the directory path of the file relative to the help tree,
        //like 'Classes' or 'Tutorials'.
        dirLevel = folder.split($/).reject {|y| (y.size<1) or: (y==".")}.size;
        baseDir = ".";
        dirLevel.do { baseDir = baseDir +/+ ".." };

        footNotes = List.new;

        x = parser.findNode(\class);
        if(x.text.notEmpty, {
            name = x.text.stripWhiteSpace;
            currentClass = name.asSymbol.asClass;
            
            currentClass !? {
                cnode = this.addUndocumentedMethods(currentClass.class,\classmethods);
                inode = this.addUndocumentedMethods(currentClass,\instancemethods);
                //TODO: add methods from +ClassName.schelp (recursive search)
            };

            this.renderHTMLHeader(f,name,\class,folder,toc);

            x = parser.findNode(\description);
            this.renderHTMLSubTree(f,x);

            this.renderHTMLSubTree(f,cnode);
            this.renderHTMLSubTree(f,inode);
            
            x = parser.findNode(\examples);
            this.renderHTMLSubTree(f,x);

            parser.root.do {|n|
                if(n.tag == \section) {
                    this.renderHTMLSubTree(f,n);
                };
            };

        },{
            x = parser.findNode(\title);
            name = x.text.stripWhiteSpace;
            this.renderHTMLHeader(f,name,\other,folder,toc);
            this.renderHTMLSubTree(f,(tag:'root',children:parser.root));
        });

        this.renderFootNotes(f);

        f.write("<div class='version'>SuperCollider version "++Main.version++"</div>");
        f.write("</body></html>");
        f.close;
    }

}

ScDoc {
    classvar <>helpTargetDir;
    classvar <>helpSourceDir;
    classvar <categoryMap;
    classvar <docMap;
    classvar <p, <r;
    classvar doWait;

/*    *new {
        ^super.new.init;
    }*/
    
    *postProgress {|string|
        string.postln;
        if(doWait, {0.wait});
    }

    *docMapToJSON {|path|
        var f = File.open(path,"w");

        if(f.isNil, {^nil});
        
        f.write("docmap = [\n");
        docMap.pairsDo {|k,v|
            f.write("{\n");
            v.pairsDo {|k2,v2|
                v2=v2.asString.replace("'","\\'");
                f.write("'"++k2++"': '"++v2++"',\n");
            };

            f.write("},\n");
        };
        f.write("]\n");
        f.close;
    }

    *splitList {|txt|
//        ^txt.findRegexp("[-_>#a-zA-Z0-9]+[-_>#/a-zA-Z0-9 ]*[-_>#/a-zA-Z0-9]+").flop[1];
        ^txt.findRegexp("[^, ]+[^,]*[^, ]+").flop[1];
    }

    *initClass {
        helpTargetDir = thisProcess.platform.userAppSupportDir +/+ "/Help";
        helpSourceDir = thisProcess.platform.systemAppSupportDir +/+ "/HelpSource";
        r = ScDocRenderer.new;
        r.parser = p = ScDocParser.new;
        doWait = false;
    }

    *makeOverviews {
        var mets, f, n;
        
        ScDoc.postProgress("Generating ClassTree...");
        p.overviewClassTree;
        r.renderHTML(helpTargetDir +/+ "Overviews/ClassTree.html","Overviews",false);

        ScDoc.postProgress("Generating Class overview...");
        p.overviewAllClasses(docMap);
        r.renderHTML(helpTargetDir +/+ "Overviews/Classes.html","Overviews",false);

        ScDoc.postProgress("Generating Methods overview...");
        mets = p.overviewAllMethods(docMap);
        r.renderHTML(helpTargetDir +/+ "Overviews/Methods.html","Overviews",false);

        ScDoc.postProgress("Writing Methods JSON index...");
        f = File.open(ScDoc.helpTargetDir +/+ "methods.js","w");
        f.write("methods = [\n");
        mets.pairsDo {|k,v|
            f.write("['"++k++"',[");
            v.do {|c,i|
                n = c[0];
                if(n.find("Meta_")==0, {n = n.drop(5)});
                f.write("'"++n++"',");
            };
            f.write("]],\n");
        };
        f.write("\n];");
        f.close;

        ScDoc.postProgress("Generating Documents overview...");
        p.overviewAllDocuments(docMap);
        r.renderHTML(helpTargetDir +/+ "Overviews/Documents.html","Overviews", false);

        ScDoc.postProgress("Generating Categories overview...");
        p.overviewCategories(categoryMap);
        r.renderHTML(helpTargetDir +/+ "Overviews/Categories.html","Overviews", true);

//        ScDoc.postProgress("Generating Server overview...");
//        p.overviewServer(categoryMap);
//        r.renderHTML(helpTargetDir +/+ "Overviews/Server.html","Overviews");
    }

    *makeMethodList {|c,n,tag|
        var l, mets, name, syms;

        (mets = c.methods) !? {
            n.add((tag:tag, children:l=List.new));
            syms = mets.collectAs(_.name,IdentitySet);
            mets.do {|m| //need to iterate over mets to keep the order
                name = m.name;
                if (name.isSetter.not or: {syms.includes(name.asGetter).not}) {
                    l.add((tag:\method, text:name.asString));
                };
            };
        };
    }

    *classHasArKrIr {|c|
        ^#[\ar,\kr,\ir].collect {|m| c.class.findRespondingMethodFor(m).notNil }.reduce {|a,b| a or: b};
    }
    
    *makeArgString {|m|
        var res = "";
        var value;
        m.argNames.do {|a,i|
            if (i>0) { //skip 'this' (first arg)
                if (i>1) { res = res ++ ", " };
                res = res ++ a;
                value = m.prototypeFrame[i];
                if (value.notNil) {
                    value = switch(value.class,
                        Symbol, { "'"++value.asString++"'" },
                        Char, { "$"++value.asString },
                        String, { "\""++value.asString++"\"" },
                        { value.asString }
                    );
                    res = res ++ " = " ++ value.asString;
                };
            };
        };
        if (res.notEmpty) {
            ^("("++res++")");
        } {
            ^"";
        };
    }

    *handleUndocumentedClasses {|force=false|
        var n, m, name, cats;
        var src, dest;
        var srcbase = helpSourceDir +/+ "Classes";
        var destbase = helpTargetDir +/+ "Classes";
        ScDoc.postProgress("Checking for undocumented classes...");
        Class.allClasses.do {|c|
            name = c.name.asString;
            src = srcbase +/+ name ++ ".schelp";

//            if(File.exists(src).not and: {name.find("Meta_")!=0}, {
            if(docMap["Classes" +/+ name].isNil and: {name.find("Meta_")!=0}, { //this was actually slower!
            //FIXME: doesn't work quite right in case one removes the src file, then it's still in docMap cache..
                dest = destbase +/+ name ++ ".html";
                n = List.new;
                n.add((tag:\class, text:name));
                n.add((tag:\summary, text:""));

                cats = "Undocumented classes";
                if(this.classHasArKrIr(c), {
                    cats = cats ++ ", UGens>Undocumented";
                    if(c.categories.notNil) {
                        cats = cats ++ ", "++c.categories.join(", ");
                    };
                });
                n.add((tag:\categories, text:cats));

                p.root = n;
                this.addToDocMap(p, "Classes" +/+ name);
                
                if((force or: File.exists(dest).not), {
                    ScDoc.postProgress("Generating doc for class: "++name);
                    n.add((tag:\description, children:m=List.new));
                    m.add((tag:\prose, text:"This class is missing documentation. Please create and edit "++src, display:\block));
                    c.helpFilePath !? {
                        m.add((tag:\prose, text:"Old help file: ", display:\block));
                        m.add((tag:\link, text:c.helpFilePath, display:\inline));
                    };

                    this.makeMethodList(c.class,n,\classmethods);
                    this.makeMethodList(c,n,\instancemethods);
                    r.renderHTML(dest,"Classes");
                });
            });
            n = docMap["Classes" +/+ name];
            n !? {n.delete = false};
        };
    }

    *addToDocMap {|parser, path|
        var x = parser.findNode(\class).text;
        var doc = (
            path:path,
            summary:parser.findNode(\summary).text,
            categories:parser.findNode(\categories).text
        );

        doc.title = if(x.notEmpty,x,{parser.findNode(\title).text});
        
        docMap[path] = doc;
    }

    *makeCategoryMap {
        var cats, c;
        ScDoc.postProgress("Creating category map...");
        categoryMap = Dictionary.new;
        docMap.pairsDo {|k,v|
            cats = ScDoc.splitList(v.categories);
            cats = cats ? ["Uncategorized"];
            cats.do {|cat|
                if (categoryMap[cat].isNil) {
                    categoryMap[cat] = List.new;
                };
                categoryMap[cat].add(v);
            };

            // check if class is standard, extension or missing
            if(v.path.dirname=="Classes") {
                c = v.path.basename.asSymbol.asClass;
                v.installed = if(c.notNil) {
                    if(c.filenameSymbol.asString.beginsWith(thisProcess.platform.classLibraryDir).not)
                        {\extension}
                        {\standard}
                } {\missing};
            };
        };

    }

    *readDocMap {
        var path = helpTargetDir +/+ "scdoc_cache";
        if((docMap = Object.readArchive(path)).notNil, {
            ^false;
        }, {
            docMap = Dictionary.new;
            ScDoc.postProgress("Creating new docMap cache");
            ^true;
        });
        
    }

    *writeDocMap {
        var path = helpTargetDir +/+ "scdoc_cache";
        docMap.writeArchive(path);
    }

    *updateFile {|source,force=false|
        var lastDot = source.findBackwards(".");
        var subtarget = source.copyRange(helpSourceDir.size+1,lastDot-1);
        var target = helpTargetDir +/+ subtarget ++".html";
        var folder = target.dirname;
        var ext = source.copyToEnd(lastDot);
        if(ext == ".schelp", {
            if(force or: {docMap[subtarget].isNil} or: {("test"+source.escapeChar($ )+"-ot"+target.escapeChar($ )).systemCmd!=0}, { //update only if needed
                p.parseFile(source);
                this.addToDocMap(p,subtarget);
                r.renderHTML(target,subtarget.dirname);
            });
            docMap[subtarget].delete = false;
        }, {
            ScDoc.postProgress("Copying" + source + "to" + folder);
            ("mkdir -p"+folder.escapeChar($ )).systemCmd;
            ("cp" + source.escapeChar($ ) + folder.escapeChar($ )).systemCmd;
        });
    
    }

    *updateAll {|force=false,doneFunc=nil,threaded=false|
        var f = {
            if(force.not, {
                force = this.readDocMap;
            }, {
                docMap = Dictionary.new;
            });
            
            docMap.do{|e|
                e.delete = true;
            };

            PathName(helpSourceDir).filesDo {|path|
                this.updateFile(path.fullPath, force);
            };
            this.handleUndocumentedClasses(force);
            docMap.pairsDo{|k,e|
                if(e.delete==true, {
                    ScDoc.postProgress("Deleting "++e.path);
                    docMap.removeAt(k);
                    //TODO: we should also remove the rendered dest file if existent?
                });
                e.removeAt(\delete); //remove the key since we don't need it anymore
            };
            this.writeDocMap;
            this.makeCategoryMap;
            this.makeOverviews;
            this.postProgress("Writing Document JSON index...");
            this.docMapToJSON(this.helpTargetDir +/+ "docmap.js");

            "ScDoc done!".postln;
            doneFunc.value();
            doWait=false;
        };
        if(doWait = threaded, {
            Routine(f).play(AppClock);
        }, f);
    }
    
    *findClassOrMethod {|str|
        ^ ScDoc.helpTargetDir +/+ if(str[0].isUpper,
            {"Classes" +/+ str ++ ".html"},
            {"Overviews/Methods.html#" ++ str}
        );
    }
}

+ String {
    stripWhiteSpace {
        var a=0, b=this.size-1;
        //FIXME: how does this handle strings that are empty or single characters?
        while({(this[a]==$\n) or: (this[a]==$\ ) or: (this[a]==$\t)},{a=a+1});
        while({(this[b]==$\n) or: (this[b]==$\ ) or: (this[b]==$\t)},{b=b-1});
        ^this.copyRange(a,b);
    }
}

+ Method {
    isExtensionOf {|class|
        ^(
            (this.filenameSymbol != class.filenameSymbol)
            and:
                if((class!=Object) and: (class!=Meta_Object),
                    {class.superclasses.includes(this.ownerClass).not},
                    {true})
        );
    }
}
