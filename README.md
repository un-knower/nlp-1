# nlp #

网页正文抽取，关键词抽取，命名实体识别, 情感分析等Web数据挖掘分析所需要的基础模块的HTTP服务接口，可以通过HTTP方式，提供相关服务。

## 编译运行 ##

通过sbt进行测试：

```sh
$ cd nlp
$ sbt
> runMain HTTP
```

打包成包含有所有依赖的单一jar文件并运行测试：

```sh
$ cd nlp
$ sbt assembly
$ cd target/scala-2.11
$ scala nlp.jar --help
```

If `browse` doesn't launch your browser, manually open [http://localhost:8080/api/extract?url=http://news.ifeng.com/a/20161003/50058514_0.shtml](http://localhost:8080/api/extract?url=http://news.ifeng.com/a/20161003/50058514_0.shtml) in your browser.
