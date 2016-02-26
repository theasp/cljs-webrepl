#!/bin/bash

set -ex

# Variables
ORIGIN_URL=`git config --get remote.origin.url`

# Set identity
git config user.name "Automated Deployment"
git config user.email "auto@example.com"

# Delete existing gh-pages branch
if git branch | grep -q gh-pages; then
  git branch -D gh-pages
fi

# Make new gh-pages branch
git checkout --orphan gh-pages

# Move everything to .build
rm -rf .build
mkdir .build
mv * .build

# Move what we want into place
mv .build/resources/public/* .
mv .build/target/cljsbuild/public/* .
rm -rf js/out

# Remove the old files
git rm --cached -r .
rm -rf .build

# Push to gh-pages.
git add -fA
git commit --allow-empty -m "Automated Deployment [ci skip]"
git push -f $ORIGIN_URL gh-pages

# Move back to previous branch.
git checkout -

echo "Deployed Successfully!"

exit 0
