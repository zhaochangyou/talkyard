/**
 * Copyright (C) 2014 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/// <reference path="../../../typedefs/angularjs/angular.d.ts" />
/// <reference path="../../../typedefs/lodash/lodash.d.ts" />
/// <reference path="../../../shared/plain-old-javascript.d.ts" />
/// <reference path="../ForumModule.ts" />

//------------------------------------------------------------------------------
   module debiki2.forum {
//------------------------------------------------------------------------------
var d = { i: debiki.internal, u: debiki.v0.util };


class CategoryController {

  public static $inject = ['$scope', 'CategoryService'];
  constructor(private $scope: CategoryScope, private categoryService: CategoryService) {
    $scope.selectedCategories = categoryService.selectedCategories;
    $scope.allMainCategories = categoryService.allMainCategories;
    $scope.mv = this;

    // I'll port all AngularJS stuff to React later on. For now:
    $scope.currentUser = debiki2.ReactStore.getUser();
  }


  public changeCategory(newCategorySlug: string) {
    this.categoryService.changeCategory(newCategorySlug);
  }


  public get selectedCategoryId() {
    var anySelectedCategory = _.last<Category>(this.$scope.selectedCategories);
    if (!anySelectedCategory) {
      return null;
    }
    return anySelectedCategory.pageId;
  }


  public get selectedCategoryOrForumId() {
    var anyCategoryId = this.selectedCategoryId;
    if (!anyCategoryId) {
      // Return the forum id.
      // For now, call out to React. I'll rewrite from Angular to React later anyway.
      return debiki2.ReactStore.getPageId();
    }
    return anyCategoryId;
  }


  public editCategory() {
    // No idea why, but in Chrome, this:
    //   window.open('/-' + this.selectedCategoryOrForumId, '_self');
    // results in an error:
    //   "Script on the page used too much memory. reload to enable scripts again"
    // However, this works fine:
    location.href = '/-' + this.selectedCategoryOrForumId;
  }


  public createCategory() {
    this.createChildPage('ForumCategory');
  }


  public createTopic() {
    this.createChildPage('ForumTopic');
  }


  private createChildPage(role: string) {
    var anyReturnToUrl = window.location.toString().replace(/#/, '__dwHash__');
    d.i.loginIfNeeded('LoginToCreateTopic', anyReturnToUrl, () => {
      // (Now we might be outside Angular.apply() but that's fine.)
      openEditorToCreatePage(this.selectedCategoryOrForumId, role);
    });
  }

}


function openEditorToCreatePage(parentPageId: string, role: string) {
  d.i.withEditorScope(function(editorScope) {
    editorScope.vm.editNewForumPage(parentPageId, role);
  });
};


forum.forumModule.controller("CategoryController", CategoryController);

//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=tcqwn list