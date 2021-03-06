#!/bin/bash

set -ex

# Variables
ORIGIN_URL=`git config --get remote.origin.url`
GOOGLETAG="
<!-- Google Tag Manager -->
<noscript><iframe src=\"//www.googletagmanager.com/ns.html?id=GTM-W84GVX\"
height=\"0\" width=\"0\" style=\"display:none;visibility:hidden\"></iframe></noscript>
<script>(function(w,d,s,l,i){w[l]=w[l]||[];w[l].push({'gtm.start':
new Date().getTime(),event:'gtm.js'});var f=d.getElementsByTagName(s)[0],
j=d.createElement(s),dl=l!='dataLayer'?'&l='+l:'';j.async=true;j.src=
'//www.googletagmanager.com/gtm.js?id='+i+dl;f.parentNode.insertBefore(j,f);
})(window,document,'script','dataLayer','GTM-W84GVX');</script>
<!-- End Google Tag Manager -->"

# Set identity
git config user.name "Automated Deployment"
git config user.email "auto@example.com"

# Delete existing gh-pages branch
if git branch | grep -q gh-pages; then
  git branch -D gh-pages
fi

# Make new gh-pages branch
git checkout --orphan gh-pages

# Move everything to .dist
rm -rf .dist
mkdir .dist
mv * .dist

# Move what we want into place
mv .dist/resources/public/* .
mv .dist/target/cljsbuild/public/js .
for i in js/*.min.js; do
  mv $i ${i%.min.js}.js
done

# Remove the old files
git rm --cached -r .
rm -rf .dist

# Add google tag
perl -p -i -e "s%<!-- GOOGLETAG -->%${GOOGLETAG}%" index.html

# Push to gh-pages.
git add -fA
git commit --allow-empty -m "Automated Deployment [ci skip]"
git push -f $ORIGIN_URL gh-pages

# Move back to previous branch.
git checkout -

echo "Deployed Successfully!"

exit 0
